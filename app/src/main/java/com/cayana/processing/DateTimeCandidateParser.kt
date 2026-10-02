package com.cayana.processing

import com.cayana.memory.model.EventCandidate

interface DateTimeCandidateParser {
    suspend fun parseCandidates(text: String): List<EventCandidate>
}

class DefaultDateTimeCandidateParser : DateTimeCandidateParser {
    override suspend fun parseCandidates(text: String): List<EventCandidate> {
        // Stage 0 stub: returns empty list until Event Safety rules are implemented in later stage
        return emptyList()
    }
}
