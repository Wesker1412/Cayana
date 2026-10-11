package com.cayana.retrieval

/**
 * Coarse confidence indicator for the retrieval result.
 * Downstream layers (e.g. Stage 8 Ask) use this to avoid hallucinating when no relevant memories exist.
 */
enum class RetrievalConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}
