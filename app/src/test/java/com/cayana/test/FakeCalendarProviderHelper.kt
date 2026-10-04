package com.cayana.test

import com.cayana.calendar.CalendarProviderHelper
import com.cayana.calendar.CalendarTarget

class FakeCalendarProviderHelper(
    var hasPermission: Boolean = true,
    var hasWritePermission: Boolean = true,
    var availableCalendars: List<CalendarTarget> = listOf(
        CalendarTarget(id = "1", displayName = "Personal", isPrimary = true),
        CalendarTarget(id = "2", displayName = "Work", isPrimary = false)
    )
) : CalendarProviderHelper {

    override fun hasCalendarPermission(): Boolean = hasPermission

    override fun hasWriteCalendarPermission(): Boolean = hasWritePermission

    override suspend fun isCalendarValidAndWritable(calendarId: Long): Boolean {
        if (!hasWritePermission) return false
        return availableCalendars.any { it.id == calendarId.toString() }
    }

    override suspend fun getWritableCalendars(): List<CalendarTarget> {
        if (!hasPermission) return emptyList()
        return availableCalendars
    }
}
