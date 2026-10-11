package com.cayana.actions.destination

import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.DestinationType
import com.cayana.memory.model.MemoryItem

interface NoteDestination : ActionDestination {
    override val destinationType: DestinationType get() = DestinationType.NOTE

    suspend fun createNote(candidate: ActionCandidate): DestinationExecutionResult

    override suspend fun execute(
        candidate: ActionCandidate,
        memoryItem: MemoryItem
    ): DestinationExecutionResult {
        return createNote(candidate)
    }
}
