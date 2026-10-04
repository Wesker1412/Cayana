package com.cayana.event.model

data class EventEvidence(
    val rawSnippet: String,
    val matchedPattern: String,
    val role: DateRole
)
