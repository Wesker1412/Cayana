package com.cayana.event.model

import java.time.Instant

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW
}

object EventActionPolicy {
    const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
    const val MEDIUM_CONFIDENCE_THRESHOLD = 0.50f

    fun evaluate(candidate: EventCandidate, referenceTime: Instant): ConfidenceLevel {
        // 1. Business hours, deadlines, sale periods, or unknown roles can never be HIGH/MEDIUM events
        if (candidate.dateRole != DateRole.EVENT_TIME) {
            return ConfidenceLevel.LOW
        }

        // 2. Must have valid start time
        val startAt = candidate.startAt ?: return ConfidenceLevel.LOW

        // 3. Past events must not be automatically added
        if (startAt.isBefore(referenceTime)) {
            return ConfidenceLevel.LOW
        }

        // 4. Broken or empty title
        val title = candidate.title?.trim()
        if (title.isNullOrBlank() || title.length < 2) {
            return ConfidenceLevel.LOW
        }

        // 5. Evaluate confidence score
        return when {
            candidate.confidence >= HIGH_CONFIDENCE_THRESHOLD -> ConfidenceLevel.HIGH
            candidate.confidence >= MEDIUM_CONFIDENCE_THRESHOLD -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }
}
