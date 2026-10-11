package com.cayana.ask.llm

import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.ContextPack

/**
 * Encapsulates the request payload passed to an LLM provider.
 * Strictly decoupled from Room entities, DAOs, or database connections.
 */
data class LlmRequest(
    val query: String,
    val systemPrompt: String,
    val contextPack: ContextPack,
    val options: AskOptions = AskOptions()
)
