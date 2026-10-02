package com.cayana.memory.model

import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import java.util.UUID

data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceType: SourceType,
    val createdAt: Long = System.currentTimeMillis(),
    val capturedAt: Long = System.currentTimeMillis(),
    val title: String? = null,
    val rawText: String? = null,
    val normalizedText: String? = null,
    val sourceUri: String? = null,
    val sourceUrl: String? = null,
    val sourceExists: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
    val entities: List<String> = emptyList(),
    val eventCandidates: List<EventCandidate> = emptyList(),
    val processingState: ProcessingState = ProcessingState.COMPLETED
)
