package com.cayana.calendar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_actions",
    indices = [
        Index(value = ["memoryId", "actionType"], unique = true),
        Index(value = ["memoryId"])
    ]
)
data class CalendarActionEntity(
    @PrimaryKey
    val id: String,
    val memoryId: String,
    val calendarId: Long,
    val calendarEventId: Long?,
    val actionType: String,
    val createdAt: Long,
    val status: String,
    val title: String?,
    val startAt: Long?,
    val endAt: Long?
)
