package com.cayana.event.extractor

import com.cayana.event.model.EventCandidate
import java.time.Instant
import java.time.ZoneId

interface EventExtractor {
    fun extract(
        text: String,
        referenceTime: Instant,
        zoneId: ZoneId
    ): List<EventCandidate>
}
