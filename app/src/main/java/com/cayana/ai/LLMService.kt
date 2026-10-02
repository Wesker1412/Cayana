package com.cayana.ai

import com.cayana.memory.model.MemoryItem

data class Entitlement(
    val remainingCredits: Int = 0,
    val isSubscriber: Boolean = false
)

/**
 * Strict boundary interface:
 * Global Principle #5: LLM does not access the database directly.
 * Global Boundary: Even if Agents are added in the future, only Read-Only Memory access is allowed.
 * ReadOnlyMemoryProvider ensures only read-only views are served to AI / RAG query contexts.
 */
interface ReadOnlyMemoryProvider {
    suspend fun getRelevantMemories(query: String, limit: Int = 10): List<MemoryItem>
}

interface LLMService {
    val isConfigured: Boolean
    suspend fun askMemory(query: String, context: List<MemoryItem>): String
}

class StubLLMService : LLMService {
    override val isConfigured: Boolean = false

    override suspend fun askMemory(query: String, context: List<MemoryItem>): String {
        throw UnsupportedOperationException("LLM is not enabled in Stage 0")
    }
}
