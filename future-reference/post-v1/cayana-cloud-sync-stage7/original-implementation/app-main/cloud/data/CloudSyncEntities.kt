package com.cayana.cloud.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_sync_state")
data class CloudSyncStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val isInitialized: Boolean = false,
    val isEnabled: Boolean = false,
    val lastPullSeq: Long = 0L,
    val lastSuccessfulSyncAt: Long = 0L,
    val lastErrorCode: String? = null
)

@Entity(tableName = "cloud_memory_sync_metadata")
data class CloudMemorySyncMetadataEntity(
    @PrimaryKey
    val memoryId: String,
    val revision: Long,
    val lastSyncedRevision: Long = 0L
)

enum class CloudOutboxOperation {
    UPSERT,
    DELETE
}

@Entity(
    tableName = "cloud_sync_outbox",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["memoryId", "revision"])
    ]
)
data class CloudSyncOutboxEntity(
    @PrimaryKey
    val id: String,
    val memoryId: String,
    val revision: Long,
    val operation: String, // "UPSERT" or "DELETE"
    val createdAt: Long,
    val attemptCount: Int = 0
)
