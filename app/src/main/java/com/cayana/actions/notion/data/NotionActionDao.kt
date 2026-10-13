package com.cayana.actions.notion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface NotionActionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(action: NotionActionEntity): Long

    @Query("SELECT * FROM notion_actions WHERE id = :id")
    suspend fun getActionById(id: String): NotionActionEntity?

    @Query("SELECT * FROM notion_actions WHERE id = :id")
    suspend fun getById(id: String): NotionActionEntity?

    @Query("SELECT * FROM notion_actions WHERE sourceMemoryId = :sourceMemoryId AND actionType = :actionType AND targetId = :targetId LIMIT 1")
    suspend fun getAction(sourceMemoryId: String, actionType: String, targetId: String): NotionActionEntity?

    @Query("SELECT * FROM notion_actions WHERE sourceMemoryId = :sourceMemoryId")
    suspend fun getActionsForMemory(sourceMemoryId: String): List<NotionActionEntity>

    @Query("UPDATE notion_actions SET status = :status, destinationPageId = :destinationPageId, destinationPageUrl = :destinationPageUrl, updatedAt = :updatedAt WHERE id = :id AND status != 'IGNORED'")
    suspend fun updateStatusAndPage(
        id: String,
        status: String,
        destinationPageId: String?,
        destinationPageUrl: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE notion_actions SET status = :status, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id AND status NOT IN ('CREATED', 'IGNORED')")
    suspend fun updateStatusAndError(
        id: String,
        status: String,
        lastError: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE notion_actions SET status = :status, updatedAt = :updatedAt WHERE id = :id AND status NOT IN ('CREATED', 'IGNORED')")
    suspend fun updateStatus(
        id: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE notion_actions SET status = 'CREATING', attemptCount = attemptCount + 1, updatedAt = :updatedAt WHERE id = :id AND (status = 'FAILED' OR status = 'CREATING_ERROR') AND attemptCount < :maxAttempts")
    suspend fun claimRetryAction(
        id: String,
        maxAttempts: Int = 3,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE notion_actions SET status = 'CREATING', attemptCount = attemptCount + 1, updatedAt = :updatedAt WHERE id = :id AND status IN ('CONFIRM_PENDING', 'NEEDS_ATTENTION')")
    suspend fun claimConfirmAction(
        id: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE notion_actions SET status = 'IGNORED', updatedAt = :updatedAt WHERE id = :id AND status IN ('CONFIRM_PENDING', 'NEEDS_ATTENTION')")
    suspend fun claimIgnoreAction(
        id: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT * FROM notion_actions WHERE status = 'CONFIRM_PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingConfirmActions(): List<NotionActionEntity>

    @Query("SELECT * FROM notion_actions WHERE status = 'CREATING' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getInFlightCreatingActions(limit: Int = 10): List<NotionActionEntity>

    @Query("SELECT * FROM notion_actions WHERE status = 'NEEDS_ATTENTION' ORDER BY updatedAt ASC")
    suspend fun getNeedsAttentionActions(): List<NotionActionEntity>

    @Update
    suspend fun update(action: NotionActionEntity): Int

    @Query("DELETE FROM notion_actions WHERE sourceMemoryId = :sourceMemoryId")
    suspend fun deleteByMemoryId(sourceMemoryId: String): Int

    @Query("SELECT * FROM notion_actions")
    suspend fun getAll(): List<NotionActionEntity>

    @Query("DELETE FROM notion_actions")
    suspend fun clearAll(): Int
}
