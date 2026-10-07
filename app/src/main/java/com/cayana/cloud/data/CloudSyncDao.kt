package com.cayana.cloud.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudSyncStateDao {
    @Query("SELECT * FROM cloud_sync_state WHERE id = 1 LIMIT 1")
    suspend fun getSyncState(): CloudSyncStateEntity?

    @Query("SELECT * FROM cloud_sync_state WHERE id = 1 LIMIT 1")
    fun observeSyncState(): Flow<CloudSyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: CloudSyncStateEntity)

    @Query("UPDATE cloud_sync_state SET lastPullSeq = :seq WHERE id = 1")
    suspend fun updateLastPullSeq(seq: Long)

    @Query("UPDATE cloud_sync_state SET lastSuccessfulSyncAt = :timestamp, lastErrorCode = NULL WHERE id = 1")
    suspend fun recordSuccessfulSync(timestamp: Long)

    @Query("UPDATE cloud_sync_state SET lastErrorCode = :errorCode WHERE id = 1")
    suspend fun recordSyncError(errorCode: String?)

    @Query("UPDATE cloud_sync_state SET isEnabled = :enabled WHERE id = 1")
    suspend fun setSyncEnabled(enabled: Boolean)
}

@Dao
interface CloudMemorySyncMetadataDao {
    @Query("SELECT * FROM cloud_memory_sync_metadata WHERE memoryId = :memoryId LIMIT 1")
    suspend fun getMetadata(memoryId: String): CloudMemorySyncMetadataEntity?

    @Query("SELECT * FROM cloud_memory_sync_metadata")
    suspend fun getAllMetadata(): List<CloudMemorySyncMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: CloudMemorySyncMetadataEntity)

    @Query("UPDATE cloud_memory_sync_metadata SET lastSyncedRevision = :syncedRevision WHERE memoryId = :memoryId")
    suspend fun updateLastSyncedRevision(memoryId: String, syncedRevision: Long)

    @Query("DELETE FROM cloud_memory_sync_metadata WHERE memoryId = :memoryId")
    suspend fun deleteMetadata(memoryId: String): Int
}

@Dao
interface CloudSyncOutboxDao {
    @Query("SELECT * FROM cloud_sync_outbox ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingBatch(limit: Int): List<CloudSyncOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: CloudSyncOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueAll(items: List<CloudSyncOutboxEntity>)

    @Query("DELETE FROM cloud_sync_outbox WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM cloud_sync_outbox WHERE memoryId = :memoryId AND revision = :revision")
    suspend fun deleteByMemoryIdAndRevision(memoryId: String, revision: Long): Int

    @Query("UPDATE cloud_sync_outbox SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun incrementAttemptCount(id: String)

    @Query("SELECT count(*) FROM cloud_sync_outbox")
    suspend fun getPendingCount(): Int

    @Query("DELETE FROM cloud_sync_outbox")
    suspend fun clearAll(): Int
}
