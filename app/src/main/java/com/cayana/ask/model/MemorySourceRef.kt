package com.cayana.ask.model

import com.cayana.source.SourceType

/**
 * Safe public citation reference for a memory source used in an Ask response.
 * Strictly excludes private Room entity fields, local file paths, URIs, and database internal IDs.
 */
data class MemorySourceRef(
    val memoryId: String,
    val title: String?,
    val sourceType: SourceType,
    val capturedAt: Long
)
