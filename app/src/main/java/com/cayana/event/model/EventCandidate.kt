package com.cayana.event.model

import java.time.Instant
import java.time.ZoneId

data class EventCandidate(
    val title: String?,
    val startAt: Instant?,
    val endAt: Instant?,
    val location: String?,
    val isAllDay: Boolean = false,
    val confidence: Float,
    val evidence: List<EventEvidence> = emptyList(),
    val dateRole: DateRole,
    val endTimeInferred: Boolean = false,
    val zoneId: ZoneId? = null
)
