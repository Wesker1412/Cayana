package com.cayana.retrieval

/**
 * Centralized policy governing resource and context bounds for local memory retrieval.
 * Guarantees that retrieval operations cannot return unbounded candidate pools or context.
 */
object RetrievalPolicy {
    /** Default number of memories to return to downstream LLM/caller */
    const val DEFAULT_TOP_K: Int = 5

    /** Hard upper limit on top-K returned memories (caller requesting 1000 will be clamped to 8) */
    const val MAX_TOP_K: Int = 8

    /** Maximum candidate pool size retrieved from the lexical index before reranking */
    const val MAX_CANDIDATES: Int = 50

    /** Maximum characters extracted per memory for the relevant excerpt */
    const val MAX_EXCERPT_CHARS_PER_MEMORY: Int = 1200

    /** Hard ceiling on total combined context characters across all retrieved items */
    const val MAX_TOTAL_CONTEXT_CHARS: Int = 7000
}
