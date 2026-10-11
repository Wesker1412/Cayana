package com.cayana.ask.llm

import com.cayana.ask.model.EvidenceState

/**
 * Normalized response returned by an LLM provider.
 */
data class LlmResponse(
    val answer: String,
    val evidenceState: EvidenceState,
    val citedMemoryIds: List<String> = emptyList(),
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
)
