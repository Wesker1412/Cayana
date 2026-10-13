package com.cayana.actions.notion.destination

import com.cayana.actions.destination.NoteDestination
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.actions.notion.auth.NotionAuthStatus
import com.cayana.actions.notion.auth.NotionAuthorizationManager
import com.cayana.actions.notion.client.NotionClient
import com.cayana.actions.notion.client.NotionTargetType
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.UUID

class NotionDestination(
    private val notionAuthManager: NotionAuthorizationManager,
    private val notionClient: NotionClient,
    private val settingsRepository: SettingsRepository,
    private val logger: CayanaLogger
) : NoteDestination {

    companion object {
        private const val TAG = "NotionDestination"
        const val ACTION_TYPE_CREATE = "NOTION_PAGE_CREATE"
    }

    override suspend fun isConfigured(): Boolean {
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        return settings?.isNotionConnected == true &&
                settings.autoNotionEnabled &&
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

    override suspend fun createNote(candidate: ActionCandidate): DestinationExecutionResult {
        if (!isConfigured()) {
            logger.d(TAG, "Notion destination is not configured; skipping creation for memory ${candidate.sourceMemoryId}")
            return DestinationExecutionResult.Failure(
                reason = "Notion destination is not configured",
                canRetry = false
            )
        }

        if (!isAuthorized()) {
            logger.w(TAG, "Notion destination is not authorized; needs attention for memory ${candidate.sourceMemoryId}")
            return DestinationExecutionResult.NeedsAttention(
                reason = "Notion authorization required"
            )
        }

        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        val targetId = settings?.selectedNotionTargetId
        if (targetId.isNullOrBlank()) {
            logger.w(TAG, "No Notion target page/database selected")
            return DestinationExecutionResult.NeedsAttention(
                reason = "No target Notion page or database selected"
            )
        }

        val tokenResult = notionAuthManager.getAccessToken()
        val token = when (tokenResult) {
            is Result.Success -> tokenResult.data
            is Result.Error -> {
                logger.w(TAG, "Failed retrieving Notion access token: ${tokenResult.exception.javaClass.simpleName}")
                return DestinationExecutionResult.NeedsAttention(
                    reason = "Notion authorization required: ${tokenResult.exception.message}"
                )
            }
            is Result.Loading -> {
                return DestinationExecutionResult.NeedsAttention(
                    reason = "Notion authorization in progress"
                )
            }
        }

        val targetType = if (settings.selectedNotionTargetType?.equals("database", ignoreCase = true) == true) {
            NotionTargetType.DATABASE
        } else {
            NotionTargetType.PAGE
        }

        logger.i(TAG, "Creating Notion note for memory ${candidate.sourceMemoryId} in target $targetId")

        return when (val createResult = notionClient.createPage(
            accessToken = token,
            targetId = targetId,
            targetType = targetType,
            title = candidate.title,
            content = candidate.notes,
            sourceMemoryId = candidate.sourceMemoryId
        )) {
            is Result.Success -> {
                val page = createResult.data
                logger.i(TAG, "Successfully created Notion note (externalId=${page.id})")
                DestinationExecutionResult.Success(
                    destinationReferenceId = page.id
                )
            }
            is Result.Error -> {
                val exception = createResult.exception
                if (exception is NotionAuthRequiredException) {
                    logger.w(TAG, "Notion auth expired or revoked during note creation")
                    notionAuthManager.markAuthRequired()
                    settingsRepository.updateNotionAuthStatus(NotionAuthStatus.AUTH_REQUIRED)
                    DestinationExecutionResult.NeedsAttention(
                        reason = "Notion authorization expired or revoked"
                    )
                } else {
                    val canRetry = exception is IOException
                    logger.w(TAG, "Failed creating note in Notion: ${exception.message} (canRetry=$canRetry)")
                    DestinationExecutionResult.Failure(
                        reason = exception.message ?: "Failed creating note in Notion",
                        canRetry = canRetry
                    )
                }
            }
            is Result.Loading -> {
                DestinationExecutionResult.Failure(
                    reason = "Note creation in progress",
                    canRetry = true
                )
            }
        }
    }
}
