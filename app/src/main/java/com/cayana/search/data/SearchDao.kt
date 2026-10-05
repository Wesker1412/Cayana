package com.cayana.search.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.data.MemoryFtsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entity: MemoryFtsEntity)

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

    @Query("SELECT memoryId FROM memories_fts WHERE memories_fts MATCH :ftsQuery")
    suspend fun searchMemoryIds(ftsQuery: String): List<String>
}
