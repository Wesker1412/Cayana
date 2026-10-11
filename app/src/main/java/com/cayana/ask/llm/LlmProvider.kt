package com.cayana.ask.llm

/**
 * Pluggable interface for LLM backends (Local offline, Cloud, Mock).
 * Decoupled from all storage, network client details, and database layers.
 */
interface LlmProvider {
    val info: LlmProviderInfo

    /**
     * Indicates whether this provider is currently available and deployed.
     * Defaults to true (e.g. for local/offline mock providers and test doubles).
     */
    val isEnabled: Boolean get() = true

    /**
     * Executes generation for the bounded request.
     */
    suspend fun generateAnswer(request: LlmRequest): LlmResponse
}

/**
 * Transport wire budget validator interface reserved for Stage 8B remote HTTP providers.
 *
 * NOTE ON BUDGET BOUNDARIES:
 * - ContextPack.promptContextBytes enforces the local context prompt budget.
 * - In Stage 8B, remote HTTP providers must independently validate the final full HTTP payload
 *   (JSON envelope, system prompt, headers, parameters) against the transport wire byte budget
 *   immediately before network transmission.
 */
interface HttpWireBudgetValidator {
    /**
     * Verifies that the final serialized HTTP request payload fits strictly within the transport wire limit.
     */
    fun validateFinalHttpRequestBytes(serializedPayloadBytes: Int, maxTransportBytes: Int): Boolean
}
