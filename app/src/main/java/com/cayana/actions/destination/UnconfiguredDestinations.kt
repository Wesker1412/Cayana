package com.cayana.actions.destination

import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.DestinationExecutionResult

class UnconfiguredTaskDestination : TaskDestination {
    override suspend fun isConfigured(): Boolean = false
    override suspend fun isAuthorized(): Boolean = false

    override suspend fun createTask(candidate: ActionCandidate): DestinationExecutionResult {
        return DestinationExecutionResult.Failure(
            reason = "Task destination is not configured in Cayana Stage 9A",
            canRetry = false
        )
    }
}

class UnconfiguredNoteDestination : NoteDestination {
    override suspend fun isConfigured(): Boolean = false
    override suspend fun isAuthorized(): Boolean = false

    override suspend fun createNote(candidate: ActionCandidate): DestinationExecutionResult {
        return DestinationExecutionResult.Failure(
            reason = "Note destination is not configured in Cayana Stage 9A",
            canRetry = false
        )
    }
}
