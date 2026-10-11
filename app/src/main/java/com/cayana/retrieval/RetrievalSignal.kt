package com.cayana.retrieval

/**
 * Signals that contributed to a memory candidate's relevance during deterministic reranking.
 * Used for explainability, metrics, and debugging without leaking plaintext user content.
 */
enum class RetrievalSignal {
    EXACT_TITLE,
    TITLE_MATCH,
    BODY_MATCH,
    ENTITY_MATCH,
    EVENT_TITLE_MATCH,
    EVENT_LOCATION_MATCH,
    EVENT_DATE_MATCH,
    CAPTURE_DATE_MATCH,
    SOURCE_HINT_MATCH,
    URL_MATCH
}
