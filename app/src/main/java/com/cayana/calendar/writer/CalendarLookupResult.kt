package com.cayana.calendar.writer

sealed interface CalendarLookupResult {
    data class Found(val eventId: Long) : CalendarLookupResult
    data object NotFound : CalendarLookupResult
    data class Unavailable(val cause: Throwable? = null) : CalendarLookupResult
}
