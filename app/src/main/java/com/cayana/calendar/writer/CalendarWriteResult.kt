package com.cayana.calendar.writer

sealed class CalendarWriteResult {
    data class Success(val calendarEventId: Long) : CalendarWriteResult()
    data class Failure(val reason: String, val throwable: Throwable? = null) : CalendarWriteResult()
}
