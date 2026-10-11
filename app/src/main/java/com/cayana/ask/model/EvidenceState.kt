package com.cayana.ask.model

/**
 * Three-state evidence classification for Cayana Ask:
 * - SUFFICIENT_EVIDENCE: Context contains sufficient factual evidence to directly answer the query.
 * - INSUFFICIENT_EVIDENCE: Context contains relevant memories, but specific facts needed to answer are missing or inconclusive.
 * - NO_EVIDENCE: No relevant memories found in local storage.
 */
enum class EvidenceState {
    SUFFICIENT_EVIDENCE,
    INSUFFICIENT_EVIDENCE,
    NO_EVIDENCE
}
