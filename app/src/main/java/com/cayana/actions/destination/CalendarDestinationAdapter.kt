package com.cayana.actions.destination

import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.calendar.CalendarProcessingCoordinator
import com.cayana.calendar.CalendarProviderHelper
import com.cayana.memory.model.MemoryItem
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.first

class CalendarDestinationAdapter(
    private val coordinator: CalendarProcessingCoordinator,
    private val calendarProviderHelper: CalendarProviderHelper,
    private val settingsRepository: SettingsRepository
) : CalendarDestination {

    override suspend fun isConfigured(): Boolean {
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        return settings?.autoCalendarEnabled == true && !settings.selectedCalendarId.isNullOrBlank()
    }

    override suspend fun isAuthorized(): Boolean {
        return calendarProviderHelper.hasWriteCalendarPermission()
    }

    override suspend fun createCalendarEvent(
        candidate: ActionCandidate,
        memoryItem: MemoryItem
    ): DestinationExecutionResult {
        val handled = coordinator.process(
            memoryItem = memoryItem,
            rawText = candidate.notes ?: memoryItem.rawText
        )
        return if (handled) {
            DestinationExecutionResult.Success(
                destinationReferenceId = null,
                message = "Handled by Calendar pipeline"
            )
        } else {
            DestinationExecutionResult.Failure(
                reason = "Calendar processing skipped or returned false",
                canRetry = false
            )
        }
    }
}
