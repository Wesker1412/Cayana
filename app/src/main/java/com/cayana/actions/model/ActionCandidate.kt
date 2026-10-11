package com.cayana.actions.model

import java.time.Instant

/**
 * Universal action candidate extracted from Memory.
 *
 * Guaranteed boundary:
 * Contains only bounded candidate fields necessary for action creation and routing.
 * Does NOT contain entire raw private memory text, full source URIs, or credentials.
 */
data class ActionCandidate(
    val type: ActionCandidateType,
    val title: String,
    val sourceMemoryId: String,
    val confidence: Float,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val dueAt: Instant? = null,
    val location: String? = null,
    val notes: String? = null,
    val requiresConfirmation: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)
