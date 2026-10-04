package com.cayana.calendar

/**
 * Interface to inspect and validate calendars on the device.
 */
interface CalendarProviderHelper {
    /**
     * Checks if the app currently has permission to read calendars.
     */
    fun hasCalendarPermission(): Boolean

    /**
     * Checks if the app currently has permission to write calendars.
     */
    fun hasWriteCalendarPermission(): Boolean

    /**
     * Validates whether a calendar with the given ID exists and has at least CONTRIBUTOR access level.
     */
    suspend fun isCalendarValidAndWritable(calendarId: Long): Boolean

    /**
     * Retrieves all available writable calendars from the device's Calendar Provider.
     * Returns an empty list if permission is missing, denied, or no writable calendars exist.
     */
    suspend fun getWritableCalendars(): List<CalendarTarget>
}
