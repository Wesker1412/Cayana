package com.cayana.memory.model

enum class EventConfidence {
    HIGH,
    MEDIUM,
    LOW
}

data class EventCandidate(
    val title: String,
    val startTimestamp: Long,
    val endTimestamp: Long? = null,
    val location: String? = null,
    val confidence: EventConfidence = EventConfidence.LOW,
    val rawMatchedSnippet: String? = null
)
