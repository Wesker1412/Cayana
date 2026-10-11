package com.cayana.actions.tasks.destination

import com.cayana.actions.destination.TaskDestination
import com.cayana.actions.model.ActionCandidate
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
        private const val MAX_NOTES_SNIPPET_LENGTH = 300

        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    override suspend fun isConfigured(): Boolean {
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        return settings?.isGoogleTasksConnected == true &&
                settings.autoTasksEnabled &&
                !settings.selectedTaskListId.isNullOrBlank()
    }

    override suspend fun isAuthorized(): Boolean {
        return tasksAuthManager.authStatus.value == TasksAuthStatus.CONNECTED
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
            logger.w(TAG, "Failed loading user settings: ${e.message}")
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
            logger.w(TAG, "Failed to query existing task action: ${e.message}")
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
                    logger.w(TAG, "Existing task action in NEEDS_ATTENTION state for memory ${candidate.sourceMemoryId}")
                    return@withContext DestinationExecutionResult.NeedsAttention(
                        reason = existingAction.lastError ?: "Previous task creation state requires attention"
                    )
                }
                "CREATING" -> {
                    logger.w(TAG, "Task creation is currently in progress for memory ${candidate.sourceMemoryId}")
                    return@withContext DestinationExecutionResult.Failure(
                        reason = "Task creation is currently in progress",
                        canRetry = false
                    )
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
                logger.w(TAG, "Duplicate task action reservation prevented: ${e.message}")
                return@withContext DestinationExecutionResult.Success(
                    destinationReferenceId = null,
                    message = "Duplicate reservation prevented"
                )
            }
        }

        // 4. Acquire valid Access Token
        val token = when (val tokenResult = tasksAuthManager.getAccessToken()) {
            is Result.Success -> tokenResult.data
            is Result.Error -> {
                logger.w(TAG, "Failed obtaining access token: ${tokenResult.exception.message}")
                taskActionDao.updateStatusAndError(actionId, "FAILED", tokenResult.exception.message)
                return@withContext DestinationExecutionResult.NeedsAttention(
                    reason = "Google Tasks access token required: ${tokenResult.exception.message}"
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
            Result.Loading -> DestinationExecutionResult.Failure("Creating task", canRetry = true)
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
        when (exception) {
            is TasksHttpException.Unauthorized -> {
                logger.w(TAG, "Received 401 Unauthorized from Google Tasks API; invalidating token")
                tasksAuthManager.invalidateCachedAccessToken()
                tasksAuthManager.markAuthRequired()
                taskActionDao.updateStatusAndError(actionId, "FAILED", "401 Unauthorized")
                return DestinationExecutionResult.NeedsAttention("Google Tasks 授權已失效，需要重新驗證。")
            }
            is TasksHttpException.RateLimited -> {
                logger.w(TAG, "Received 429 Rate Limited from Google Tasks API")
                taskActionDao.updateStatusAndError(actionId, "FAILED", "429 Rate Limited")
                return DestinationExecutionResult.Failure("Google Tasks 請求過於頻繁，請稍後重試。", canRetry = true)
            }
            is TasksHttpException.ServerError -> {
                logger.w(TAG, "Received 5xx Server Error from Google Tasks API: ${exception.message}")
                taskActionDao.updateStatusAndError(actionId, "FAILED", exception.message)
                return DestinationExecutionResult.Failure("Google Tasks 伺服器暫時發生錯誤，請稍後重試。", canRetry = true)
            }
            is IOException -> {
                // Network timeout or connection interrupted: POST may have been delivered!
                // DO NOT blindly retry POST. Query remote task list to verify first.
                logger.w(TAG, "POST timed out or connection lost; querying remote list to avoid duplicate creation")
                val queryResult = try {
                    tasksClient.findTaskByMemoryRef(token, taskListId, candidate.sourceMemoryId, cleanTitle)
                } catch (e: Exception) {
                    Result.Error(e)
                }

                if (queryResult is Result.Success && queryResult.data != null) {
                    val recoveredTask = queryResult.data
                    taskActionDao.updateStatusAndTaskId(actionId, "CREATED", recoveredTask.id)
                    logger.i(TAG, "Recovered task ${recoveredTask.id} after timeout without duplicate creation")
                    return DestinationExecutionResult.Success(
                        destinationReferenceId = recoveredTask.id,
                        message = "已建立至 Google Tasks（連線中斷後已自動確認並復原，未重複建立）。"
                    )
                }

                // If remote task status cannot be confirmed, mark as NEEDS_ATTENTION. Never blindly retry!
                logger.e(TAG, "Remote task status unconfirmed after network failure; marking NEEDS_ATTENTION")
                taskActionDao.updateStatusAndError(
                    actionId,
                    "NEEDS_ATTENTION",
                    "POST delivered but response timed out; remote task unconfirmed: ${exception.message}"
                )
                return DestinationExecutionResult.NeedsAttention(
                    reason = "待辦事項建立逾時且無法確認遠端狀態，已標記為 NeedsAttention 以防重複建立。"
                )
            }
            else -> {
                logger.e(TAG, "Unexpected error creating task: ${exception.message}")
                taskActionDao.updateStatusAndError(actionId, "FAILED", exception.message)
                return DestinationExecutionResult.Failure("建立待辦事項失敗：${exception.message}", canRetry = true)
            }
        }
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
