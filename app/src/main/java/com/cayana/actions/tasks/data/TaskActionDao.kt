package com.cayana.actions.tasks.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskActionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(action: TaskActionEntity): Long

    @Query("SELECT * FROM task_actions WHERE id = :id")
    suspend fun getById(id: String): TaskActionEntity?

    @Query("SELECT * FROM task_actions WHERE sourceMemoryId = :sourceMemoryId AND actionType = :actionType AND taskListId = :taskListId LIMIT 1")
    suspend fun getAction(sourceMemoryId: String, actionType: String, taskListId: String): TaskActionEntity?

    @Query("SELECT * FROM task_actions WHERE sourceMemoryId = :sourceMemoryId")
    suspend fun getActionsForMemory(sourceMemoryId: String): List<TaskActionEntity>

    @Query("UPDATE task_actions SET status = :status, destinationTaskId = :destinationTaskId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatusAndTaskId(
        id: String,
        status: String,
        destinationTaskId: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE task_actions SET status = :status, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatusAndError(
        id: String,
        status: String,
        lastError: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE task_actions SET status = 'CREATING', attemptCount = attemptCount + 1, updatedAt = :updatedAt WHERE id = :id AND (status = 'FAILED' OR status = 'CREATING_ERROR')")
    suspend fun claimRetryAction(
        id: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT * FROM task_actions WHERE status = 'CREATING' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getInFlightCreatingActions(limit: Int = 10): List<TaskActionEntity>

    @Update
    suspend fun update(action: TaskActionEntity): Int

    @Query("DELETE FROM task_actions WHERE sourceMemoryId = :sourceMemoryId")
    suspend fun deleteByMemoryId(sourceMemoryId: String): Int

    @Query("SELECT * FROM task_actions")
    suspend fun getAll(): List<TaskActionEntity>

    @Query("DELETE FROM task_actions")
    suspend fun clearAll(): Int
}
