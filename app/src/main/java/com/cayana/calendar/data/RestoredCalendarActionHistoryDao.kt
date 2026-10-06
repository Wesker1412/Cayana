package com.cayana.calendar.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RestoredCalendarActionHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<RestoredCalendarActionHistoryEntity>)

    @Query("SELECT * FROM restored_calendar_action_history WHERE memoryId = :memoryId")
    suspend fun getByMemoryId(memoryId: String): List<RestoredCalendarActionHistoryEntity>

    @Query("SELECT * FROM restored_calendar_action_history")
    suspend fun getAll(): List<RestoredCalendarActionHistoryEntity>

    @Query("DELETE FROM restored_calendar_action_history")
    suspend fun clearAll()
}
