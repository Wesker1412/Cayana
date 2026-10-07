package com.cayana.memory.repository

import com.cayana.memory.model.MemoryItem
import kotlinx.coroutines.flow.Flow

/**
 * Core interface for Memory Layer storage.
 * Can be cleanly substituted with alternative implementations (e.g. in-memory fake for tests,
 * encrypted local storage, or mock repository).
 */
enum class MutationOrigin {
    LOCAL,
    RESTORE
}

interface MemoryRepository {
    fun getAllMemories(): Flow<List<MemoryItem>>
    fun getMemoryById(id: String): Flow<MemoryItem?>
    suspend fun getMemoryBySourceUri(sourceUri: String): MemoryItem?
    suspend fun saveMemory(
        item: MemoryItem,
        origin: MutationOrigin = MutationOrigin.LOCAL
    )
    suspend fun deleteMemory(
        id: String,
        origin: MutationOrigin = MutationOrigin.LOCAL
    )
    fun searchMemories(query: String): Flow<List<MemoryItem>>
    fun getMemoryCount(): Flow<Int>
    suspend fun markSourceExists(id: String, exists: Boolean)
    suspend fun getMemoriesForReconciliation(
        sourceType: com.cayana.source.SourceType,
        cursorTimestamp: Long,
        limit: Int = 25
    ): List<MemoryItem>
    suspend fun getMemoriesForCompoundReconciliation(
        sourceType: com.cayana.source.SourceType,
        cursorCapturedAt: Long,
        cursorId: String,
        limit: Int = 25
    ): List<MemoryItem>
    suspend fun clearAll()
    suspend fun rebuildSearchIndex()
    fun isIndexRebuildNeeded(): Boolean = false
}
