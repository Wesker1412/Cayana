package com.cayana.ask.service

import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.AskResult

/**
 * Application-layer entry point for Cayana Ask workflows.
 * Orchestrates: Question → MemoryRetriever → ContextPack → LlmProvider → Answer + Memory Sources.
 */
interface AskService {
    /**
     * Indicates whether the underlying LLM provider is available and enabled.
     */
    val isEnabled: Boolean get() = true

    /**
     * Submits a question, retrieves private bounded memory context,
     * builds a sanitized ContextPack, checks authorization, and invokes the configured LLM provider.
     */
    suspend fun ask(
        query: String,
        options: AskOptions = AskOptions()
    ): AskResult
}
