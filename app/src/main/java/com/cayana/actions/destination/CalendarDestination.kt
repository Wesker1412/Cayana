package com.cayana.actions.destination

import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.DestinationType
import com.cayana.memory.model.MemoryItem

interface CalendarDestination : ActionDestination {
    override val destinationType: DestinationType get() = DestinationType.CALENDAR

    suspend fun createCalendarEvent(
        candidate: ActionCandidate,
        memoryItem: MemoryItem
    ): DestinationExecutionResult

    override suspend fun execute(
        candidate: ActionCandidate,
        memoryItem: MemoryItem
    ): DestinationExecutionResult {
        return createCalendarEvent(candidate, memoryItem)
    }
}
