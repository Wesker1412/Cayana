package com.cayana.calendar.writer

interface CalendarWriter {
    suspend fun createEvent(candidate: ValidatedEvent): CalendarWriteResult
    suspend fun deleteEvent(eventId: Long): Result<Unit>
    suspend fun findEventByActionId(actionId: String): Long?
}
