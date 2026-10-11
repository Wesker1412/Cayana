package com.cayana.actions.model

sealed class DestinationExecutionResult {
    data class Success(
        val destinationReferenceId: String?,
        val message: String = ""
    ) : DestinationExecutionResult()

    data class Failure(
        val reason: String,
        val canRetry: Boolean = false
    ) : DestinationExecutionResult()

    data class NeedsAttention(
        val reason: String
    ) : DestinationExecutionResult()

    data object Skipped : DestinationExecutionResult()
}
