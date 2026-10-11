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

    /**
     * Internal heuristic character budget across retrieved memory text fields (title, excerpt,
     * URL, event summary) used for candidate pruning during retrieval.
     *
     * NOTE: This is an internal candidate pruning ceiling and does NOT represent the complete
     * JSON wire-serialized length. Downstream Stage 8 ContextPack will independently enforce
     * the total transmission budget.
     */
    const val MAX_TOTAL_CONTEXT_CHARS: Int = 7000

    /** Maximum allowed characters for raw query input to prevent heavy regex / memory allocation */
    const val MAX_RAW_QUERY_CHARS: Int = 300

    /** Maximum number of content terms extracted from user query */
    const val MAX_CONTENT_TERMS: Int = 8

    /** Maximum number of internal sub-queries executed during candidate generation */
    const val MAX_MULTI_QUERIES: Int = 6

    /** Maximum character length for a generated FTS match expression */
    const val MAX_FTS_EXPRESSION_CHARS: Int = 200

    /** Maximum SQL candidate limit for an individual sub-query */
    const val MAX_SQL_CANDIDATE_LIMIT_PER_QUERY: Int = 20

    /** Current search index format version (v2 includes entities, event candidates, and date tokens) */
    const val CURRENT_INDEX_FORMAT_VERSION: Int = 2
}
