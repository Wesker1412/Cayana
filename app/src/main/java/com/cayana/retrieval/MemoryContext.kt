package com.cayana.retrieval

import com.cayana.source.SourceType

/**
 * Bounded context contract for downstream LLM callers.
 * Strictly decoupled from Room entities, DAOs, repositories, and private credentials.
 */
data class MemoryContext(
    val memoryId: String,
    val sourceType: SourceType,
    val capturedAt: Long,
    val title: String?,
    val relevantExcerpt: String,
    val sourceUrl: String? = null,
    val sourceExists: Boolean = true,
    val relevanceScore: Double,
    val matchedSignals: Set<RetrievalSignal> = emptySet(),
    val eventSummary: String? = null
)
