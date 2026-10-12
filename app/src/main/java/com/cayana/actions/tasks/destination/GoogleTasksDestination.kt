package com.cayana.actions.tasks.destination

import com.cayana.actions.destination.TaskDestination
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.tasks.auth.TasksAuthStatus
import com.cayana.actions.tasks.auth.TasksAuthorizationManager
import com.cayana.actions.tasks.client.GoogleTasksClient
import com.cayana.actions.tasks.client.TasksHttpException
import com.cayana.actions.tasks.data.TaskActionDao
import com.cayana.actions.tasks.data.TaskActionEntity
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class GoogleTasksDestination(
    private val taskActionDao: TaskActionDao,
    private val tasksAuthManager: TasksAuthorizationManager,
    private val tasksClient: GoogleTasksClient,
    private val settingsRepository: SettingsRepository,
    private val logger: CayanaLogger
) : TaskDestination {

    companion object {
        private const val TAG = "GoogleTasksDestination"
        const val ACTION_TYPE_CREATE = "GOOGLE_TASKS_CREATE"
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_NOTES_SNIPPET_LENGTH = 1000

        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    override suspend fun isConfigured(): Boolean {
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        return settings?.isGoogleTasksConnected == true &&
                settings.autoTasksEnabled &&
                !settings.selectedTaskListId.isNullOrBlank()
    }

    override suspend fun isAuthorized(): Boolean {
        if (tasksAuthManager.authStatus.value == TasksAuthStatus.CONNECTED) {
            return true
        }
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        if (settings?.googleTasksAuthStatus != TasksAuthStatus.CONNECTED) {
            return false
        }
        val refreshed = tasksAuthManager.refreshAuthStatus()
        if (refreshed != TasksAuthStatus.CONNECTED) {
            settingsRepository.updateGoogleTasksAuthStatus(refreshed)
        }
        return refreshed == TasksAuthStatus.CONNECTED
    }

    override suspend fun createTask(candidate: ActionCandidate): DestinationExecutionResult {
        return createTaskInternal(candidate, ZoneId.systemDefault())
    }

    suspend fun createTaskInternal(
        candidate: ActionCandidate,
        zoneId: ZoneId
    ): DestinationExecutionResult = withContext(Dispatchers.IO) {
        val settings = try {
            settingsRepository.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "Failed loading user settings: ${e.javaClass.simpleName}")
            return@withContext DestinationExecutionResult.Failure("Settings unavailable", canRetry = false)
        }

        // 1. Authorization check: must be connected
        if (tasksAuthManager.authStatus.value != TasksAuthStatus.CONNECTED || settings.googleTasksAuthStatus != TasksAuthStatus.CONNECTED) {
            logger.w(TAG, "Google Tasks authorization missing or required")
            return@withContext DestinationExecutionResult.NeedsAttention(
                reason = "Google Tasks authorization required"
            )
        }

        // 2. Configuration check: must be enabled and have a target list selected
        if (!settings.autoTasksEnabled || settings.selectedTaskListId.isNullOrBlank()) {
            logger.d(TAG, "Google Tasks destination is not fully configured or enabled; skipping transmission")
            return@withContext DestinationExecutionResult.Failure(
                reason = "Google Tasks destination is not configured",
                canRetry = false
            )
        }

        val taskListId = settings.selectedTaskListId ?: "@default"
        val taskListTitle = settings.selectedTaskListTitle

        // 3. Persistent Deduplication and atomic reservation
        val existingAction = try {
            taskActionDao.getAction(candidate.sourceMemoryId, ACTION_TYPE_CREATE, taskListId)
        } catch (e: Exception) {
            logger.w(TAG, "Failed to query existing task action: ${e.javaClass.simpleName}")
            null
        }

        val actionId: String
        if (existingAction != null) {
            when (existingAction.status) {
                "CREATED" -> {
                    logger.i(TAG, "Task already created for memory ${candidate.sourceMemoryId} in list $taskListId")
                    return@withContext DestinationExecutionResult.Success(
                        destinationReferenceId = existingAction.destinationTaskId,
                        message = "Task already created (idempotent)"
                    )
                }
                "NEEDS_ATTENTION" -> {
                    logger.w(TAG, "Existing task action in NEEDS_ATTENTION state for memory ${candidate.sourceMemoryId}; attempting reconciliation")
                    val token = when (val tokenResult = tasksAuthManager.getAccessToken()) {
                        is Result.Success -> tokenResult.data
                        else -> return@withContext DestinationExecutionResult.NeedsAttention(
                            reason = existingAction.lastError ?: "Previous task creation state requires attention"
                        )
                    }
                    val reconcileResult = reconcileUncertainPost(
                        existingAction.id,
                        token,
                        taskListId,
                        candidate,
                        existingAction.title
                    )
                    if (reconcileResult is DestinationExecutionResult.Success) {
                        return@withContext reconcileResult
                    }
                    return@withContext DestinationExecutionResult.NeedsAttention(
                        reason = "無法確認 Google Tasks 是否已建立待辦；為避免重複，不會自動重送。"
                    )
                }
                "CREATING" -> {
                    val isStale = (System.currentTimeMillis() - existingAction.updatedAt) > 30_000L
                    if (isStale) {
                        logger.w(TAG, "Stale CREATING action found for memory ${candidate.sourceMemoryId}; reconciling with remote list")
                        val token = when (val tokenResult = tasksAuthManager.getAccessToken()) {
                            is Result.Success -> tokenResult.data
                            else -> {
                                taskActionDao.updateStatusAndError(existingAction.id, "NEEDS_ATTENTION", "Reconciliation: auth missing")
                                return@withContext DestinationExecutionResult.NeedsAttention("Google Tasks 授權失效。")
                            }
                        }
                        return@withContext reconcileUncertainPost(
                            existingAction.id,
                            token,
                            taskListId,
                            candidate,
                            existingAction.title
                        )
                    } else {
                        logger.w(TAG, "Task creation is currently in progress for memory ${candidate.sourceMemoryId}")
                        return@withContext DestinationExecutionResult.Failure(
                            reason = "Task creation is currently in progress",
                            canRetry = false
                        )
                    }
                }
                "CONFIRM_PENDING" -> {
                    logger.i(TAG, "Task action for memory ${candidate.sourceMemoryId} is awaiting user confirmation; auto-execution forbidden")
                    return@withContext DestinationExecutionResult.Skipped
                }
                "IGNORED" -> {
                    logger.i(TAG, "Task action for memory ${candidate.sourceMemoryId} was ignored by user; skipping")
                    return@withContext DestinationExecutionResult.Skipped
                }
                else -> {
                    // Status is FAILED or CREATING_ERROR: atomic claim
                    val claimed = taskActionDao.claimRetryAction(existingAction.id)
                    if (claimed != 1) {
                        logger.i(TAG, "Another execution claimed retry for action ${existingAction.id}")
                        return@withContext DestinationExecutionResult.Failure(
                            reason = "Another execution claimed retry",
                            canRetry = false
                        )
                    }
                    actionId = existingAction.id
                }
            }
        } else {
            actionId = UUID.randomUUID().toString()
            val cleanTitle = candidate.title.trim().take(MAX_TITLE_LENGTH)
            val googleDue = candidate.dueAt?.let { formatGoogleDue(it, zoneId) }
            val formattedNotes = buildNotes(candidate, zoneId)

            val reservation = TaskActionEntity(
                id = actionId,
                sourceMemoryId = candidate.sourceMemoryId,
                actionType = ACTION_TYPE_CREATE,
                taskListId = taskListId,
                taskListTitle = taskListTitle,
                destinationTaskId = null,
                status = "CREATING",
                title = cleanTitle,
                originalDueAt = candidate.dueAt?.toEpochMilli(),
                googleDue = googleDue,
                notes = formattedNotes,
                attemptCount = 1
            )

            try {
                taskActionDao.insert(reservation)
            } catch (e: Exception) {
                // A uniqueness conflict means another attempt owns the reservation, not that
                // the remote task was created. A database error is likewise never success.
                logger.w(TAG, "Task reservation not acquired: ${e.javaClass.simpleName}")
                return@withContext DestinationExecutionResult.NeedsAttention(
                    reason = "無法確認待辦建立狀態；不會重複送出。"
                )
            }
        }

        // 4. Acquire valid Access Token
        val token = when (val tokenResult = tasksAuthManager.getAccessToken()) {
            is Result.Success -> tokenResult.data
            is Result.Error -> {
                logger.w(TAG, "Failed obtaining access token: ${tokenResult.exception.javaClass.simpleName}")
                taskActionDao.updateStatusAndError(actionId, "FAILED", "Authorization required")
                return@withContext DestinationExecutionResult.NeedsAttention(
                    reason = "Google Tasks 授權失效，需要重新授權。"
                )
            }
            Result.Loading -> return@withContext DestinationExecutionResult.Failure("Token loading", canRetry = true)
        }

        // 5. Dispatch to Google Tasks API
        val cleanTitle = candidate.title.trim().take(MAX_TITLE_LENGTH)
        val googleDue = candidate.dueAt?.let { formatGoogleDue(it, zoneId) }
        val notesToSend = buildNotes(candidate, zoneId)

        val createResult = try {
            tasksClient.createTask(token, taskListId, cleanTitle, notesToSend, googleDue)
        } catch (e: Exception) {
            Result.Error(e)
        }

        when (createResult) {
            is Result.Success -> {
                val createdTask = createResult.data
                taskActionDao.updateStatusAndTaskId(actionId, "CREATED", createdTask.id)
                logger.i(TAG, "Successfully created Google Task ${createdTask.id} for memory ${candidate.sourceMemoryId}")
                DestinationExecutionResult.Success(
                    destinationReferenceId = createdTask.id,
                    message = "已建立至 Google Tasks。說明：Google Tasks 官方限制僅保留截止日期，原始時間已保存於備忘與 Cayana 本機紀錄中。"
                )
            }
            is Result.Error -> {
                val ex = createResult.exception
                handleCreateFailure(actionId, token, taskListId, candidate, cleanTitle, ex)
            }
            Result.Loading -> {
                taskActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Remote result unavailable")
                DestinationExecutionResult.NeedsAttention("待辦建立結果尚未確認；請先確認遠端狀態。")
            }
        }
    }

    /**
     * Executes a task action that was explicitly confirmed by the user.
     * Atomically claims the action from CONFIRM_PENDING to CREATING,
     * verifies destination task list consistency, and creates the remote task.
     */
    suspend fun executeConfirmedTask(actionId: String): DestinationExecutionResult = withContext(Dispatchers.IO) {
        val action = taskActionDao.getById(actionId)
        if (action == null) {
            logger.w(TAG, "executeConfirmedTask: action not found for id=$actionId")
            return@withContext DestinationExecutionResult.Failure("Action not found", canRetry = false)
        }

        if (action.status == "CREATED") {
            return@withContext DestinationExecutionResult.Success(
                destinationReferenceId = action.destinationTaskId,
                message = "Task already created"
            )
        }
        if (action.status == "IGNORED") {
            return@withContext DestinationExecutionResult.Failure("Action was ignored", canRetry = false)
        }
        if (action.status != "CONFIRM_PENDING") {
            logger.w(TAG, "executeConfirmedTask: action $actionId is in state ${action.status}, not CONFIRM_PENDING")
            return@withContext DestinationExecutionResult.Failure(
                "Action is not pending confirmation (status=${action.status})",
                canRetry = false
            )
        }

        if (!isAuthorized()) {
            logger.w(TAG, "executeConfirmedTask: Google Tasks authorization missing")
            return@withContext DestinationExecutionResult.NeedsAttention("Google Tasks 授權失效")
        }

        val settings = try {
            settingsRepository.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "executeConfirmedTask: failed loading settings: ${e.javaClass.simpleName}")
            return@withContext DestinationExecutionResult.Failure("Failed loading settings", canRetry = true)
        }

        // Destination consistency check: must match action.taskListId!
        if (settings.selectedTaskListId != action.taskListId) {
            logger.w(TAG, "executeConfirmedTask: destination task list mismatch (current=${settings.selectedTaskListId}, action=${action.taskListId})")
            taskActionDao.updateStatusAndError(actionId, "CANCELLED", "目標待辦清單已變更，舊確認已失效")
            return@withContext DestinationExecutionResult.Failure(
                reason = "目標待辦清單已變更，舊確認已失效",
                canRetry = false
            )
        }

        val claimed = taskActionDao.claimConfirmAction(actionId)
        if (claimed != 1) {
            logger.i(TAG, "executeConfirmedTask: action $actionId already claimed or decided")
            return@withContext DestinationExecutionResult.Failure(
                reason = "Another execution claimed or decided this action",
                canRetry = false
            )
        }

        val token = when (val tokenResult = tasksAuthManager.getAccessToken()) {
            is Result.Success -> tokenResult.data
            else -> {
                taskActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Token acquisition failed")
                return@withContext DestinationExecutionResult.NeedsAttention("無法取得 Google Tasks 授權權杖")
            }
        }

        val candidate = ActionCandidate(
            type = ActionCandidateType.TASK,
            title = action.title,
            confidence = 1.0f,
            dueAt = action.originalDueAt?.let { Instant.ofEpochMilli(it) },
            notes = action.notes,
            sourceMemoryId = action.sourceMemoryId,
            requiresConfirmation = false
        )

        val finalNotes = buildNotes(candidate, ZoneId.systemDefault())

        val postResult = tasksClient.createTask(
            accessToken = token,
            taskListId = action.taskListId,
            title = action.title,
            notes = finalNotes,
            dueRfc3339 = action.googleDue
        )

        when (postResult) {
            is Result.Success -> {
                val createdItem = postResult.data
                taskActionDao.updateStatusAndTaskId(actionId, "CREATED", createdItem.id)
                logger.i(TAG, "executeConfirmedTask: successfully created task ${createdItem.id} for action $actionId")
                DestinationExecutionResult.Success(
                    destinationReferenceId = createdItem.id,
                    message = "已建立至 Google Tasks。"
                )
            }
            is Result.Error -> {
                val ex = postResult.exception
                logger.w(TAG, "executeConfirmedTask: remote creation failed: ${ex.javaClass.simpleName}")
                handleCreateFailure(actionId, token, action.taskListId, candidate, action.title, ex)
            }
            Result.Loading -> {
                taskActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Remote result unavailable")
                DestinationExecutionResult.NeedsAttention("待辦建立結果尚未確認；請先確認遠端狀態。")
            }
        }
    }

    private suspend fun handleCreateFailure(
        actionId: String,
        token: String,
        taskListId: String,
        candidate: ActionCandidate,
        cleanTitle: String,
        exception: Throwable
    ): DestinationExecutionResult {
        return when (exception) {
            is TasksHttpException.Unauthorized -> {
                logger.w(TAG, "Received 401 Unauthorized from Google Tasks API; invalidating token")
                tasksAuthManager.invalidateCachedAccessToken()
                tasksAuthManager.markAuthRequired()
                taskActionDao.updateStatusAndError(actionId, "FAILED", "401 Unauthorized")
                DestinationExecutionResult.NeedsAttention("Google Tasks 授權已失效，需要重新驗證。")
            }
            is TasksHttpException.ClientError -> {
                // An explicit non-retryable 4xx response is not an uncertain POST outcome.
                logger.w(TAG, "Google Tasks rejected create request: HTTP ${exception.statusCode}")
                taskActionDao.updateStatusAndError(actionId, "FAILED", "HTTP ${exception.statusCode}")
                DestinationExecutionResult.Failure("Google Tasks 拒絕建立待辦。", canRetry = false)
            }
            else -> {
                // An HTTP 429/5xx, timeout, malformed response or unexpected transport
                // failure may follow a successful remote write. Never blindly POST again.
                logger.w(TAG, "Google Tasks create outcome uncertain: ${exception.javaClass.simpleName}")
                reconcileUncertainPost(actionId, token, taskListId, candidate, cleanTitle)
            }
        }
    }

    private suspend fun reconcileUncertainPost(
        actionId: String,
        token: String,
        taskListId: String,
        candidate: ActionCandidate,
        cleanTitle: String
    ): DestinationExecutionResult {
        val remoteResult = try {
            tasksClient.findTaskByMemoryRef(token, taskListId, candidate.sourceMemoryId, cleanTitle)
        } catch (e: Exception) {
            logger.w(TAG, "Remote task reconciliation failed: ${e.javaClass.simpleName}")
            Result.Error(e)
        }

        if (remoteResult is Result.Success && remoteResult.data != null) {
            val recoveredTask = remoteResult.data
            taskActionDao.updateStatusAndTaskId(actionId, "CREATED", recoveredTask.id)
            return DestinationExecutionResult.Success(
                destinationReferenceId = recoveredTask.id,
                message = "已確認遠端待辦建立成功，未重複建立。"
            )
        }

        // A negative/incomplete remote list query is NOT proof the POST was rejected:
        // the list can be paginated, stale, or temporarily unavailable.
        taskActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Remote POST outcome unconfirmed")
        return DestinationExecutionResult.NeedsAttention(
            reason = "無法確認 Google Tasks 是否已建立待辦；為避免重複，不會自動重送。"
        )
    }

    private fun formatGoogleDue(dueAt: Instant, zoneId: ZoneId): String {
        // Google Tasks REST API v1 RFC 3339 date string (midnight UTC)
        val localDate = dueAt.atZone(zoneId).toLocalDate()
        return "${localDate}T00:00:00.000Z"
    }

    private fun buildNotes(candidate: ActionCandidate, zoneId: ZoneId): String {
        val sb = StringBuilder()
        if (!candidate.notes.isNullOrBlank()) {
            sb.append(candidate.notes.take(MAX_NOTES_SNIPPET_LENGTH)).append("\n\n")
        }
        if (candidate.dueAt != null) {
            val zdt = candidate.dueAt.atZone(zoneId)
            val formattedTime = DATE_FORMATTER.format(zdt)
            sb.append("提醒時間: $formattedTime (Google Tasks 限制僅支援日期，精確時間已記錄於此備忘)\n")
        }
        sb.append("來源記憶: cayana://memory/${candidate.sourceMemoryId}")
        return sb.toString()
    }
}
