package com.cayana.retrieval

/**
 * Core interface for the Cayana Local Retrieval Layer.
 * Serves as the sole retrieval contract for all downstream LLM connectors and ask workflows.
 * Exposes bounded, private memory context without exposing Room entities, DAOs, or database capabilities.
 */
interface MemoryRetriever {
    /**
     * Executes local memory retrieval for the given query.
     * Guarantees bounded Top-K results and bounded total context length.
     *
     * @param query Natural language user query and optional retrieval parameters.
     * @return Bounded RetrievalResult containing ranked MemoryContext items.
     */
    suspend fun retrieve(query: RetrievalQuery): RetrievalResult
}
