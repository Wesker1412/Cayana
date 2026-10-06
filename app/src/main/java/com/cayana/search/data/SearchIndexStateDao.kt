package com.cayana.search.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SearchIndexStateDao {

    @Query("SELECT * FROM search_index_state WHERE id = 1")
    suspend fun getState(): SearchIndexStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: SearchIndexStateEntity)

    @Transaction
    suspend fun markDirty() {
        val current = getState() ?: SearchIndexStateEntity(id = 1)
        upsertState(
            current.copy(
                isDirty = true,
                pendingRepairs = current.pendingRepairs + 1,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    @Transaction
    suspend fun clearDirtyIfNoPending() {
        val current = getState() ?: return
        val newPending = (current.pendingRepairs - 1).coerceAtLeast(0)
        upsertState(
            current.copy(
                isDirty = newPending > 0,
                pendingRepairs = newPending,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    @Transaction
    suspend fun forceClearDirty() {
        val current = getState() ?: SearchIndexStateEntity(id = 1)
        upsertState(
            current.copy(
                isDirty = false,
                pendingRepairs = 0,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    @Query("SELECT isDirty FROM search_index_state WHERE id = 1")
    suspend fun isDirty(): Boolean?
}
