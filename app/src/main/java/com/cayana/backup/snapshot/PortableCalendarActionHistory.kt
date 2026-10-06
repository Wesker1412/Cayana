package com.cayana.backup.snapshot

import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.calendar.data.RestoredCalendarActionHistoryEntity

/**
 * Canonical immutable historical model representing a Calendar Action across multi-generational backups.
 * Maintains stable [originalActionId] identity to prevent identity drift across device hops (A -> B -> C).
 */
data class PortableCalendarActionHistory(
    val originalActionId: String,
    val memoryId: String,
    val calendarId: Long,
    val calendarEventId: Long?,
    val actionType: String,
    val createdAt: Long,
    val status: String,
    val title: String?,
    val startAt: Long?,
    val endAt: Long?,
    val location: String? = null,
    val isAllDay: Boolean = false,
    val zoneId: String? = null
) {
    companion object {
        fun fromLiveAction(action: CalendarActionEntity): PortableCalendarActionHistory {
            return PortableCalendarActionHistory(
                originalActionId = action.id,
                memoryId = action.memoryId,
                calendarId = action.calendarId,
                calendarEventId = action.calendarEventId,
                actionType = action.actionType,
                createdAt = action.createdAt,
                status = action.status,
                title = action.title,
                startAt = action.startAt,
                endAt = action.endAt,
                location = action.location,
                isAllDay = action.isAllDay,
                zoneId = action.zoneId
            )
        }

        fun fromRestoredHistory(history: RestoredCalendarActionHistoryEntity): PortableCalendarActionHistory {
            return PortableCalendarActionHistory(
                originalActionId = history.originalActionId,
                memoryId = history.memoryId,
                calendarId = history.originalCalendarId ?: 0L,
                calendarEventId = history.originalCalendarEventId,
                actionType = history.actionType,
                createdAt = history.createdAt,
                status = history.originalStatus,
                title = history.title,
                startAt = history.startAt,
                endAt = history.endAt,
                location = history.location,
                isAllDay = history.isAllDay,
                zoneId = history.zoneId
            )
        }
    }
}
