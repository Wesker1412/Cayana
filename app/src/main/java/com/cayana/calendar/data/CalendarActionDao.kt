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
    suspend fun updateStatus(id: String, status: String): Int

    @Query("UPDATE calendar_actions SET status = :status, calendarEventId = :calendarEventId WHERE id = :id")
    suspend fun updateStatusAndEventId(id: String, status: String, calendarEventId: Long?): Int

    @Query("UPDATE calendar_actions SET status = 'PROCESSING' WHERE id = :id AND status = 'PENDING'")
    suspend fun claimPendingAction(id: String): Int

    @Query("UPDATE calendar_actions SET status = 'IGNORED' WHERE id = :id AND status = 'PENDING'")
    suspend fun ignorePendingAction(id: String): Int

    @Query("UPDATE calendar_actions SET status = 'CREATING' WHERE id = :id AND (status = 'FAILED' OR status = 'CREATING_ERROR')")
    suspend fun claimRetryAction(id: String): Int

    @Query("SELECT * FROM calendar_actions WHERE status = 'CREATING' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingCreatingActions(limit: Int = 10): List<CalendarActionEntity>

    @Query("SELECT * FROM calendar_actions WHERE status IN ('CREATING', 'PROCESSING', 'PROMPT_RETRY') ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getInFlightActions(limit: Int = 10): List<CalendarActionEntity>

    @Update
    suspend fun update(action: CalendarActionEntity): Int

    @Query("DELETE FROM calendar_actions WHERE memoryId = :memoryId")
    suspend fun deleteByMemoryId(memoryId: String): Int

    @Query("SELECT * FROM calendar_actions")
    suspend fun getAll(): List<CalendarActionEntity>
}
