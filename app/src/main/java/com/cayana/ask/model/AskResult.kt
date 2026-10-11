package com.cayana.ask.model

/**
 * Result returned by AskService.
 */
sealed class AskResult {
    data class Success(
        val query: String,
        val answer: String,
        val evidenceState: EvidenceState,
        val sources: List<MemorySourceRef>,
        val contextPack: ContextPack,
        val modelUsed: String,
        val latencyMs: Long
    ) : AskResult()

    data class NoEvidence(
        val query: String,
        val message: String,
        val contextPack: ContextPack
    ) : AskResult()

    data class Unauthorized(
        val query: String,
        val reason: String,
        val contextPack: ContextPack
    ) : AskResult()

    data class Error(
        val query: String,
        val message: String,
        val cause: Throwable? = null
    ) : AskResult()
}
