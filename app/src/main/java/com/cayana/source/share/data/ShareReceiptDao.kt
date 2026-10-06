package com.cayana.source.share.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ShareReceiptDao {

    @Query("SELECT * FROM share_receipts WHERE fingerprint = :fingerprint")
    suspend fun getReceipt(fingerprint: String): ShareReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipt(receipt: ShareReceiptEntity)

    @Query("UPDATE share_receipts SET status = :status, itemIdsJson = :itemIdsJson, expiresAt = :expiresAt WHERE fingerprint = :fingerprint")
    suspend fun updateStatus(fingerprint: String, status: String, itemIdsJson: String, expiresAt: Long)

    @Query("DELETE FROM share_receipts WHERE expiresAt <= :now AND status IN ('COMPLETED', 'IGNORED', 'FAILED_PERMANENT')")
    suspend fun deleteExpired(now: Long): Int
}
