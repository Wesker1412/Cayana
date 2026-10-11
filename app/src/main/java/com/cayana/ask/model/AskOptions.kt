package com.cayana.ask.model

import java.time.Clock
import java.time.ZoneId

/**
 * Configuration options for an Ask query.
 */
data class AskOptions(
    /**
     * Budget for the retrieved context prompt text (user query + rendered memory blocks).
     * NOTE: This does NOT measure the outer HTTP JSON envelope or system prompt.
     */
    val maxContextPromptBytes: Int = DEFAULT_MAX_CONTEXT_PROMPT_BYTES,
    val topK: Int = DEFAULT_TOP_K,
    /**
     * RESTRICTED: Can ONLY be evaluated for local offline providers (isLocal == true).
     * Any attempt to bypass authorization for a remote provider will be strictly rejected.
     */
    @Deprecated("Unsafe: remote providers strictly reject authorization bypass")
    val bypassAuthorizationGate: Boolean = false,
    val clock: Clock = Clock.systemDefaultZone(),
    val zoneId: ZoneId = ZoneId.systemDefault()
) {
    /**
     * Backwards-compatible alias for prompt budget.
     */
    val maxContextBytes: Int get() = maxContextPromptBytes

    companion object {
        const val DEFAULT_MAX_CONTEXT_PROMPT_BYTES = 16 * 1024 // 16 KB context prompt budget
        const val DEFAULT_MAX_CONTEXT_BYTES = DEFAULT_MAX_CONTEXT_PROMPT_BYTES
        const val DEFAULT_TOP_K = 10
    }
}
