package com.cayana.calendar.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CalendarActionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(action: CalendarActionEntity): Long

    @Query("SELECT * FROM calendar_actions WHERE id = :id")
    suspend fun getById(id: String): CalendarActionEntity?

    @Query("SELECT * FROM calendar_actions WHERE memoryId = :memoryId AND actionType = :actionType LIMIT 1")
    suspend fun getAction(memoryId: String, actionType: String): CalendarActionEntity?

    @Query("SELECT * FROM calendar_actions WHERE memoryId = :memoryId")
    suspend fun getActionsForMemory(memoryId: String): List<CalendarActionEntity>

    @Query("UPDATE calendar_actions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Update
    suspend fun update(action: CalendarActionEntity)
}
