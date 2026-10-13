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
        when (tokenResult) {
            is Result.Success -> { /* token verified */ }
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

        // STAGE 9C-1 SCOPE BOUNDARY:
        // Stage 9C-1 focuses exclusively on Notion OAuth foundation.
        // Remote note creation is disabled and fails closed without calling NotionClient.createPage.
        logger.w(TAG, "Stage 9C-1: Remote Notion note creation is disabled (OAuth Foundation only); failing closed for memory ${candidate.sourceMemoryId}")
        return DestinationExecutionResult.Failure(
            reason = "Remote note creation is disabled in Stage 9C-1 (OAuth Foundation only)",
            canRetry = false
        )
    }
}
