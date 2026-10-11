package com.cayana.retrieval

/**
 * Benchmark instrumentation metrics for retrieval operations.
 * Contains purely operational numbers; no sensitive plaintext data is stored.
 */
data class RetrievalMetrics(
    val candidateGenerationMs: Long = 0L,
    val rerankMs: Long = 0L,
    val totalLatencyMs: Long = 0L,
    val candidateCount: Int = 0,
    val resultCount: Int = 0,
    val signalsDetected: Set<RetrievalSignal> = emptySet()
)

/**
 * Result returned by MemoryRetriever.
 */
data class RetrievalResult(
    val query: String,
    val items: List<MemoryContext>,
    val confidence: RetrievalConfidence,
    val totalCandidatesFound: Int = 0,
    val executionTimeMs: Long = 0L,
    val metrics: RetrievalMetrics? = null
)
