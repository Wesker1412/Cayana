package com.cayana.calendar

/**
 * Interface to inspect writable calendars on the device without creating events.
 */
interface CalendarProviderHelper {
    /**
     * Checks if the app currently has permission to read calendars.
     */
    fun hasCalendarPermission(): Boolean

    /**
     * Retrieves all available writable calendars from the device's Calendar Provider.
     * Returns an empty list if permission is missing, denied, or no writable calendars exist.
     */
    suspend fun getWritableCalendars(): List<CalendarTarget>
}
