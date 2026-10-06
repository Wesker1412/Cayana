package com.cayana.calendar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Inert historical storage for calendar actions restored from a Google Drive backup.
 *
 * CRITICAL SAFETY INVARIANT:
 * This table is strictly isolated from live calendar reconciliation, undo, confirm,
 * or Android Calendar Provider mutations. It serves solely as historical user reference.
 */
@Entity(
    tableName = "restored_calendar_action_history",
    indices = [
        Index(value = ["memoryId"]),
        Index(value = ["originalActionId"])
    ]
)
data class RestoredCalendarActionHistoryEntity(
    @PrimaryKey
    val id: String,
    val originalActionId: String,
    val memoryId: String,
    val actionType: String,
    val originalStatus: String,
    val createdAt: Long,
    val title: String?,
    val startAt: Long?,
    val endAt: Long?,
    val location: String? = null,
    val isAllDay: Boolean = false,
    val zoneId: String? = null,
    val originalCalendarId: Long? = null,
    val originalCalendarEventId: Long? = null,
    val restoredAt: Long = System.currentTimeMillis()
)
