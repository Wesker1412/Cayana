package com.cayana.actions.notion.destination

import com.cayana.actions.destination.NoteDestination
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.actions.notion.auth.NotionAuthStatus
import com.cayana.actions.notion.auth.NotionAuthorizationManager
import com.cayana.actions.notion.client.NotionClient
import com.cayana.actions.notion.client.NotionTargetType
import com.cayana.actions.notion.data.NotionActionDao
import com.cayana.actions.notion.data.NotionActionEntity
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.NotionTransmissionPolicy
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeoutException

class NotionDestination(
    private val notionActionDao: NotionActionDao,
    private val notionAuthManager: NotionAuthorizationManager,
    private val notionClient: NotionClient,
    private val settingsRepository: SettingsRepository,
    private val logger: CayanaLogger
) : NoteDestination {

    companion object {
        private const val TAG = "NotionDestination"
        const val ACTION_TYPE_CREATE = "NOTION_PAGE_CREATE"
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_CONTENT_LENGTH = 2000
    }

    override suspend fun isConfigured(): Boolean {
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        return settings?.isNotionConnected == true &&
                settings.notionTransmissionPolicy != NotionTransmissionPolicy.OFF &&
                !settings.selectedNotionTargetId.isNullOrBlank()
    }

    override suspend fun isAuthorized(): Boolean {
        if (notionAuthManager.authStatus.value == NotionAuthStatus.CONNECTED) {
            return true
        }
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        if (settings?.notionAuthStatus != NotionAuthStatus.CONNECTED) {
            return false
        }
        val refreshed = notionAuthManager.refreshAuthStatus()
        if (refreshed != NotionAuthStatus.CONNECTED) {
            settingsRepository.updateNotionAuthStatus(refreshed)
        }
        return refreshed == NotionAuthStatus.CONNECTED
    }

    override suspend fun createNote(candidate: ActionCandidate): DestinationExecutionResult = withContext(Dispatchers.IO) {
        val settings = try {
            settingsRepository.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "Failed loading user settings: ${e.javaClass.simpleName}")
            return@withContext DestinationExecutionResult.Failure("Settings unavailable", canRetry = false)
        }

        // 1. Configuration check
        if (settings.notionTransmissionPolicy == NotionTransmissionPolicy.OFF || settings.selectedNotionTargetId.isNullOrBlank()) {
            logger.d(TAG, "Notion destination is not configured or transmission policy is OFF")
            return@withContext DestinationExecutionResult.Failure(
                reason = "Notion destination is not configured",
                canRetry = false
            )
        }

        // 2. Authorization check
        if (!isAuthorized() || settings.notionAuthStatus != NotionAuthStatus.CONNECTED) {
            logger.w(TAG, "Notion authorization missing or required")
            return@withContext DestinationExecutionResult.NeedsAttention(
                reason = "Notion authorization required"
            )
        }

        val targetId = settings.selectedNotionTargetId
        val targetTitle = settings.selectedNotionTargetTitle
        val rawTargetType = settings.selectedNotionTargetType?.uppercase() ?: "PAGE"
        val targetType = try {
            NotionTargetType.valueOf(rawTargetType)
        } catch (_: Exception) {
            NotionTargetType.PAGE
        }

        if (targetType == NotionTargetType.DATABASE) {
            logger.w(TAG, "Selected target is a DATABASE container; direct writing unsupported in Notion 2026-03-11")
            return@withContext DestinationExecutionResult.NeedsAttention(
                reason = "所選目標為 Database，請改選其底下的 Data Source 或 Page。"
            )
        }

        // 3. Persistent Deduplication and atomic reservation
        val existingAction = try {
            val actions = notionActionDao.getActionsForMemory(candidate.sourceMemoryId)
            actions.firstOrNull { it.actionType == ACTION_TYPE_CREATE }
        } catch (e: Exception) {
            logger.w(TAG, "Failed querying existing notion actions: ${e.javaClass.simpleName}")
            null
        }

        val actionId: String
        if (existingAction != null) {
            when (existingAction.status) {
                "CREATED" -> {
                    logger.i(TAG, "Notion note already created for memory ${candidate.sourceMemoryId} in target ${existingAction.targetId}")
                    return@withContext DestinationExecutionResult.Success(
                        destinationReferenceId = existingAction.destinationPageId,
                        message = "Notion page already created (idempotent)"
                    )
                }
                "NEEDS_ATTENTION" -> {
                    logger.w(TAG, "Existing notion action in NEEDS_ATTENTION state for memory ${candidate.sourceMemoryId}; reconciling against original target ${existingAction.targetId}")
                    return@withContext reconcileAction(existingAction)
                }
                "CREATING" -> {
                    val isStale = (System.currentTimeMillis() - existingAction.updatedAt) > 30_000L
                    if (isStale) {
                        logger.w(TAG, "Stale CREATING action found for memory ${candidate.sourceMemoryId}; reconciling with remote target ${existingAction.targetId}")
                        return@withContext reconcileAction(existingAction)
                    } else {
                        logger.w(TAG, "Notion page creation is currently in progress for memory ${candidate.sourceMemoryId}")
                        return@withContext DestinationExecutionResult.Failure(
                            reason = "Notion page creation is currently in progress",
                            canRetry = false
                        )
                    }
                }
                "CONFIRM_PENDING" -> {
                    logger.i(TAG, "Notion action for memory ${candidate.sourceMemoryId} is awaiting user confirmation; auto-execution forbidden")
                    return@withContext DestinationExecutionResult.Skipped
                }
                "IGNORED" -> {
                    logger.i(TAG, "Notion action for memory ${candidate.sourceMemoryId} was ignored by user; skipping")
                    return@withContext DestinationExecutionResult.Skipped
                }
                "CANCELLED" -> {
                    logger.i(TAG, "Notion action for memory ${candidate.sourceMemoryId} was cancelled; skipping")
                    return@withContext DestinationExecutionResult.Failure(
                        reason = "Notion action was cancelled: ${existingAction.lastError}",
                        canRetry = false
                    )
                }
                else -> {
                    // FAILED or CREATING_ERROR: atomic claim
                    if (existingAction.targetId != targetId) {
                        logger.w(TAG, "Destination target mismatch for retry (original=${existingAction.targetId}, current=$targetId); stopping retry to prevent altering destination")
                        notionActionDao.updateStatusAndError(
                            existingAction.id,
                            "NEEDS_ATTENTION",
                            "目標 Notion 位置已變更（原目標：${existingAction.targetTitle}），已停止重試。"
                        )
                        return@withContext DestinationExecutionResult.NeedsAttention(
                            reason = "目標 Notion 位置已變更，為避免傳送至錯誤目的地，已停止重試。"
                        )
                    }

                    val claimed = notionActionDao.claimRetryAction(existingAction.id)
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
            val cleanContent = candidate.notes?.take(MAX_CONTENT_LENGTH)

            val reservation = NotionActionEntity(
                id = actionId,
                sourceMemoryId = candidate.sourceMemoryId,
                actionType = ACTION_TYPE_CREATE,
                targetId = targetId,
                targetTitle = targetTitle,
                targetType = targetType.name,
                destinationPageId = null,
                destinationPageUrl = null,
                status = "CREATING",
                title = cleanTitle,
                content = cleanContent,
                attemptCount = 1
            )

            try {
                notionActionDao.insert(reservation)
            } catch (e: Exception) {
                logger.w(TAG, "Notion reservation conflict or insert failed: ${e.javaClass.simpleName}")
                return@withContext DestinationExecutionResult.NeedsAttention(
                    reason = "無法確認筆記建立狀態；不會重複送出。"
                )
            }
        }

        val boundTargetId = existingAction?.targetId ?: targetId
        val boundTargetType = existingAction?.let {
            runCatching { NotionTargetType.valueOf(it.targetType) }.getOrDefault(targetType)
        } ?: targetType

        // 4. Acquire valid Access Token
        val token = when (val tokenResult = notionAuthManager.getAccessToken()) {
            is Result.Success -> tokenResult.data
            is Result.Error -> {
                logger.w(TAG, "Failed obtaining access token: ${tokenResult.exception.javaClass.simpleName}")
                notionActionDao.updateStatusAndError(actionId, "FAILED", "Authorization required")
                return@withContext DestinationExecutionResult.NeedsAttention(
                    reason = "Notion 授權失效，需要重新授權。"
                )
            }
            Result.Loading -> return@withContext DestinationExecutionResult.Failure("Token loading", canRetry = true)
        }

        // 5. Pre-POST Disconnect Gate (Race condition check)
        val currentSettings = try {
            settingsRepository.getSettings().first()
        } catch (_: Exception) { null }

        if (!isAuthorized() || notionAuthManager.authStatus.value != NotionAuthStatus.CONNECTED || currentSettings?.isNotionConnected != true) {
            logger.w(TAG, "Notion connection revoked before auto POST dispatch; aborting transmission for action $actionId")
            notionActionDao.updateStatusAndError(actionId, "CANCELLED", "Notion 已中斷連線，已停止自動建立")
            return@withContext DestinationExecutionResult.Failure(
                reason = "Notion 已中斷連線，已停止自動建立",
                canRetry = false
            )
        }

        // 6. Dispatch to Notion API
        val cleanTitle = candidate.title.trim().take(MAX_TITLE_LENGTH)
        val cleanContent = candidate.notes?.take(MAX_CONTENT_LENGTH)

        val createResult = try {
            notionClient.createPage(
                accessToken = token,
                targetId = boundTargetId,
                targetType = boundTargetType,
                title = cleanTitle,
                content = cleanContent,
                sourceMemoryId = candidate.sourceMemoryId
            )
        } catch (e: Exception) {
            Result.Error(e)
        }

        handleCreateResponse(actionId, candidate, cleanTitle, createResult)
    }

    suspend fun executeConfirmedNote(actionId: String): DestinationExecutionResult = withContext(Dispatchers.IO) {
        val action = notionActionDao.getActionById(actionId)
        if (action == null) {
            logger.w(TAG, "executeConfirmedNote: action $actionId not found")
            return@withContext DestinationExecutionResult.Failure("Action not found", canRetry = false)
        }

        if (action.status == "CREATED") {
            return@withContext DestinationExecutionResult.Success(
                destinationReferenceId = action.destinationPageId,
                message = "Note already created"
            )
        }
        if (action.status == "IGNORED") {
            return@withContext DestinationExecutionResult.Failure("Action was ignored", canRetry = false)
        }
        if (action.status != "CONFIRM_PENDING") {
            logger.w(TAG, "executeConfirmedNote: action $actionId is in state ${action.status}, not CONFIRM_PENDING")
            return@withContext DestinationExecutionResult.Failure(
                "Action is not pending confirmation (status=${action.status})",
                canRetry = false
            )
        }

        val settings = try {
            settingsRepository.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "executeConfirmedNote: failed loading settings: ${e.javaClass.simpleName}")
            return@withContext DestinationExecutionResult.Failure("Failed loading settings", canRetry = true)
        }

        if (!isAuthorized() || !settings.isNotionConnected || settings.notionAuthStatus != NotionAuthStatus.CONNECTED) {
            logger.w(TAG, "executeConfirmedNote: Notion authorization missing or disconnected")
            notionActionDao.updateStatusAndError(actionId, "CANCELLED", "Notion 連線已中斷，確認已失效")
            return@withContext DestinationExecutionResult.Failure(
                reason = "Notion 連線已中斷，無法執行筆記建立",
                canRetry = false
            )
        }

        // Destination consistency check: must match action.targetId!
        if (settings.selectedNotionTargetId != action.targetId) {
            logger.w(TAG, "executeConfirmedNote: destination target mismatch (current=${settings.selectedNotionTargetId}, action=${action.targetId})")
            notionActionDao.updateStatusAndError(actionId, "CANCELLED", "目標 Notion 位置已變更，舊確認已失效")
            return@withContext DestinationExecutionResult.Failure(
                reason = "目標 Notion 位置已變更，舊確認已失效",
                canRetry = false
            )
        }

        val claimed = notionActionDao.claimConfirmAction(actionId)
        if (claimed != 1) {
            logger.i(TAG, "executeConfirmedNote: action $actionId already claimed or decided")
            return@withContext DestinationExecutionResult.Failure(
                reason = "Another execution claimed or decided this action",
                canRetry = false
            )
        }

        val token = when (val tokenResult = notionAuthManager.getAccessToken()) {
            is Result.Success -> tokenResult.data
            else -> {
                notionActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Token acquisition failed")
                return@withContext DestinationExecutionResult.NeedsAttention("無法取得 Notion 授權權杖")
            }
        }

        val targetType = runCatching { NotionTargetType.valueOf(action.targetType) }.getOrDefault(NotionTargetType.PAGE)

        val createResult = try {
            notionClient.createPage(
                accessToken = token,
                targetId = action.targetId,
                targetType = targetType,
                title = action.title,
                content = action.content,
                sourceMemoryId = action.sourceMemoryId
            )
        } catch (e: Exception) {
            Result.Error(e)
        }

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = action.title,
            confidence = 1.0f,
            notes = action.content,
            sourceMemoryId = action.sourceMemoryId,
            requiresConfirmation = false
        )

        handleCreateResponse(actionId, candidate, action.title, createResult)
    }

    private suspend fun handleCreateResponse(
        actionId: String,
        candidate: ActionCandidate,
        cleanTitle: String,
        createResult: Result<com.cayana.actions.notion.client.NotionCreatedPage>
    ): DestinationExecutionResult {
        return when (createResult) {
            is Result.Success -> {
                val createdPage = createResult.data
                notionActionDao.updateStatusAndPage(actionId, "CREATED", createdPage.id, createdPage.url)
                logger.i(TAG, "Successfully created Notion page ${createdPage.id} for memory ${candidate.sourceMemoryId}")
                DestinationExecutionResult.Success(
                    destinationReferenceId = createdPage.id,
                    message = "已儲存至 Notion 筆記。"
                )
            }
            is Result.Error -> {
                val ex = createResult.exception
                when {
                    ex is NotionAuthRequiredException -> {
                        logger.w(TAG, "Notion 401 Unauthorized during page creation for action $actionId")
                        notionActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Notion 授權失效，請重新授權")
                        DestinationExecutionResult.NeedsAttention(reason = "Notion 授權失效，需要重新授權。")
                    }
                    ex is SecurityException -> {
                        logger.w(TAG, "Notion 403 Forbidden during page creation for action $actionId")
                        notionActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Notion 目標權限不足")
                        DestinationExecutionResult.NeedsAttention(reason = "Notion 權限不足，請確認頁面或資料庫已授權。")
                    }
                    ex is SocketTimeoutException || ex is TimeoutException -> {
                        logger.w(TAG, "Network timeout during Notion createPage for action $actionId")
                        notionActionDao.updateStatusAndError(
                            actionId,
                            "NEEDS_ATTENTION",
                            "請求逾時，無法確認遠端狀態；請先確認遠端 Notion 筆記。"
                        )
                        DestinationExecutionResult.NeedsAttention(
                            reason = "連線逾時，遠端建立結果尚未確認；已保留狀態以避免重複建立。"
                        )
                    }
                    ex is IOException -> {
                        logger.w(TAG, "Network IO error during Notion createPage: ${ex.message}")
                        notionActionDao.updateStatusAndError(actionId, "CREATING_ERROR", ex.message)
                        DestinationExecutionResult.Failure(
                            reason = "網路連線失敗: ${ex.message}",
                            canRetry = true
                        )
                    }
                    else -> {
                        logger.w(TAG, "Unexpected error during Notion createPage: ${ex.message}")
                        notionActionDao.updateStatusAndError(actionId, "FAILED", ex.message)
                        DestinationExecutionResult.Failure(
                            reason = "建立失敗: ${ex.message}",
                            canRetry = false
                        )
                    }
                }
            }
            Result.Loading -> {
                notionActionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "Remote result unavailable")
                DestinationExecutionResult.NeedsAttention("筆記建立結果尚未確認；請先確認遠端狀態。")
            }
        }
    }

    suspend fun reconcileAction(action: NotionActionEntity): DestinationExecutionResult = withContext(Dispatchers.IO) {
        if (!isAuthorized()) {
            logger.w(TAG, "reconcileAction: Notion auth missing for action ${action.id}")
            notionActionDao.updateStatusAndError(action.id, "NEEDS_ATTENTION", "Reconciliation: auth missing")
            return@withContext DestinationExecutionResult.NeedsAttention("Notion 授權失效。")
        }

        val token = when (val tokenResult = notionAuthManager.getAccessToken()) {
            is Result.Success -> tokenResult.data
            else -> {
                notionActionDao.updateStatusAndError(action.id, "NEEDS_ATTENTION", "Reconciliation: token acquisition failed")
                return@withContext DestinationExecutionResult.NeedsAttention("無法取得 Notion 授權權杖。")
            }
        }

        val searchResult = runCatching {
            notionClient.searchTargets(accessToken = token, query = action.title)
        }.getOrNull()

        if (searchResult is Result.Success) {
            val matching = searchResult.data.targets.firstOrNull { it.title.trim() == action.title.trim() }
            if (matching != null) {
                logger.i(TAG, "reconcileAction: found existing page ${matching.id} matching '${action.title}'")
                notionActionDao.updateStatusAndPage(action.id, "CREATED", matching.id, matching.url)
                return@withContext DestinationExecutionResult.Success(
                    destinationReferenceId = matching.id,
                    message = "已查證遠端 Notion 筆記存在（已恢復）。"
                )
            }
        }

        logger.w(TAG, "reconcileAction: unable to confirm remote state for action ${action.id}; retaining NEEDS_ATTENTION")
        notionActionDao.updateStatusAndError(action.id, "NEEDS_ATTENTION", "遠端狀態未確認；保留待處理狀態。")
        DestinationExecutionResult.NeedsAttention("無法確認遠端 Notion 筆記建立結果；保留待處理狀態。")
    }
}
