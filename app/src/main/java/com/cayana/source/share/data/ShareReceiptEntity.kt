package com.cayana.source.share.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "share_receipts")
data class ShareReceiptEntity(
    @PrimaryKey
    val fingerprint: String,
    val sessionId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String, // "PROCESSING", "COMPLETED"
    val itemIdsJson: String = "[]"
)
