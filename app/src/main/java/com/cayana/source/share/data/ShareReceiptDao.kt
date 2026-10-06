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

    @Query("UPDATE share_receipts SET status = :status, itemIdsJson = :itemIdsJson WHERE fingerprint = :fingerprint")
    suspend fun updateStatus(fingerprint: String, status: String, itemIdsJson: String)

    @Query("DELETE FROM share_receipts WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int
}
