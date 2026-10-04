package com.cayana.calendar.writer

import java.time.Instant
import java.time.ZoneId

data class ValidatedEvent(
    val memoryId: String,
    val calendarId: Long,
    val title: String,
    val startAt: Instant,
    val endAt: Instant,
    val location: String? = null,
    val isAllDay: Boolean = false,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val description: String? = "Added by Cayana"
)
