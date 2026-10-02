package com.cayana.calendar

/**
 * Represents a writable calendar target destination.
 * In accordance with Cayana's privacy principles, only the stable calendar ID
 * and display name are retained. User account names, emails, and profiles are never stored.
 */
data class CalendarTarget(
    val id: String,
    val displayName: String,
    val isPrimary: Boolean = false
)
