package com.cayana.search.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.data.MemoryFtsEntity
import kotlinx.coroutines.flow.Flow

import androidx.room.Transaction

@Dao
interface SearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entity: MemoryFtsEntity)

    @Transaction
    suspend fun replaceFts(document: MemoryFtsEntity) {
        deleteFtsByMemoryId(document.memoryId)
        insertFts(document)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllFts(entities: List<MemoryFtsEntity>)

    @Query("DELETE FROM memories_fts WHERE memoryId = :memoryId")
    suspend fun deleteFtsByMemoryId(memoryId: String)

    @Query("DELETE FROM memories_fts")
    suspend fun clearFts()

    @Query("SELECT COUNT(*) FROM memories_fts")
    suspend fun getFtsCount(): Int

    @Query("""
        SELECT memories.* FROM memories
        JOIN memories_fts ON memories.id = memories_fts.memoryId
        WHERE memories_fts MATCH :ftsQuery
        ORDER BY memories.capturedAt DESC
    """)
    fun searchMemoriesMatchFlow(ftsQuery: String): Flow<List<MemoryEntity>>

    @Query("""
        SELECT memories.* FROM memories
        JOIN memories_fts ON memories.id = memories_fts.memoryId
        WHERE memories_fts MATCH :ftsQuery
        ORDER BY memories.capturedAt DESC
    """)
    suspend fun searchMemoriesMatch(ftsQuery: String): List<MemoryEntity>

    @Query("""
        SELECT memories.* FROM memories
        JOIN memories_fts ON memories.id = memories_fts.memoryId
        WHERE memories_fts MATCH :ftsQuery
        ORDER BY (
            CASE
                WHEN :term != '' AND memories.title = :term THEN 3
                WHEN :term != '' AND memories.title LIKE :term || '%' THEN 2
                WHEN :term != '' AND memories.title LIKE '%' || :term || '%' THEN 1
                ELSE 0
            END
        ) DESC, memories.capturedAt DESC
        LIMIT :limit
    """)
    suspend fun searchMemoriesMatchBounded(ftsQuery: String, limit: Int, term: String = ""): List<MemoryEntity>

    @Query("SELECT memoryId FROM memories_fts WHERE memories_fts MATCH :ftsQuery")
    suspend fun searchMemoryIds(ftsQuery: String): List<String>
}
