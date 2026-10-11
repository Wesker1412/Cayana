package com.cayana.ask.llm

/**
 * Metadata identifying the LLM provider and model.
 */
data class LlmProviderInfo(
    val id: String,
    val name: String,
    val isLocal: Boolean,
    val modelName: String
)
