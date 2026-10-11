package com.cayana.ask.llm

/**
 * Pluggable interface for LLM backends (Local offline, Cloud, Mock).
 * Decoupled from all storage, network client details, and database layers.
 */
interface LlmProvider {
    val info: LlmProviderInfo

    /**
     * Executes generation for the bounded request.
     */
    suspend fun generateAnswer(request: LlmRequest): LlmResponse
}
