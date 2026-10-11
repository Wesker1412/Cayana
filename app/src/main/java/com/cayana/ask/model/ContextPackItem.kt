package com.cayana.ask.model

import com.cayana.source.SourceType

/**
 * Minimal schema for an individual memory entry inside ContextPack.
 *
 * Strict Privacy Boundaries:
 * - NO sourceUri (strictly prohibited).
 * - NO sourceUrl (stripped by default).
 * - NO local disk paths, coordinates, or database internal IDs.
 * - snippet contains only sanitized text with credentials, recovery keys, and tokens redacted.
 */
data class ContextPackItem(
    val memoryId: String,
    val snippet: String,
    val capturedAt: Long,
    val capturedDateIso: String,
    val sourceType: SourceType
)
