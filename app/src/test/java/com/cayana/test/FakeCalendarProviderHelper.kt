package com.cayana.test

import com.cayana.calendar.CalendarProviderHelper
import com.cayana.calendar.CalendarTarget

class FakeCalendarProviderHelper(
    var hasPermission: Boolean = true,
    var availableCalendars: List<CalendarTarget> = listOf(
        CalendarTarget(id = "1", displayName = "Personal", isPrimary = true),
        CalendarTarget(id = "2", displayName = "Work", isPrimary = false)
    )
) : CalendarProviderHelper {

    override fun hasCalendarPermission(): Boolean = hasPermission

    override suspend fun getWritableCalendars(): List<CalendarTarget> {
        if (!hasPermission) return emptyList()
        return availableCalendars
    }
}
