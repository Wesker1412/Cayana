package com.cayana.memory.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MemoryEntity>)

    @Query("SELECT * FROM memories ORDER BY capturedAt DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getMemoryBySourceUri(sourceUri: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    fun getMemoryByIdFlow(id: String): Flow<MemoryEntity?>

    @Query("SELECT * FROM memories WHERE (rawText LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%') ORDER BY capturedAt DESC")
    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT COUNT(*) FROM memories")
    fun getCountFlow(): Flow<Int>

    @Query("UPDATE memories SET sourceExists = :exists WHERE id = :id")
    suspend fun updateSourceExists(id: String, exists: Boolean)

    @Query("SELECT * FROM memories WHERE sourceType = :sourceType AND sourceExists = 1 AND capturedAt < :beforeCapturedAt ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun getExistingMemoriesForReconciliation(sourceType: String, beforeCapturedAt: Long, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE sourceType = :sourceType AND sourceExists = 1 ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun getInitialMemoriesForReconciliation(sourceType: String, limit: Int): List<MemoryEntity>

    @Query("""
        SELECT * FROM memories 
        WHERE sourceType = :sourceType 
          AND sourceExists = 1 
          AND (
              capturedAt < :cursorCapturedAt 
              OR (capturedAt = :cursorCapturedAt AND id < :cursorId)
          )
        ORDER BY capturedAt DESC, id DESC 
        LIMIT :limit
    """)
    suspend fun getMemoriesForCompoundReconciliation(
        sourceType: String,
        cursorCapturedAt: Long,
        cursorId: String,
        limit: Int
    ): List<MemoryEntity>

    @Delete
    suspend fun delete(entity: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}
