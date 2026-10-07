package com.cayana.memory.repository

import com.cayana.memory.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeMemoryRepository : MemoryRepository {

    private val memoriesMap = MutableStateFlow<Map<String, MemoryItem>>(emptyMap())

    override fun getAllMemories(): Flow<List<MemoryItem>> {
        return memoriesMap.map { it.values.sortedByDescending { item -> item.capturedAt } }
    }

    override fun getMemoryById(id: String): Flow<MemoryItem?> {
        return memoriesMap.map { it[id] }
    }

    override suspend fun getMemoryBySourceUri(sourceUri: String): MemoryItem? {
        return memoriesMap.value.values.firstOrNull { it.sourceUri == sourceUri }
    }

    override suspend fun saveMemory(
        item: MemoryItem,
        origin: MutationOrigin,
        remoteRevision: Long?
    ) {
        memoriesMap.update { it + (item.id to item) }
    }

    override suspend fun deleteMemory(
        id: String,
        origin: MutationOrigin,
        remoteRevision: Long?
    ) {
        memoriesMap.update { it - id }
    }

    override fun searchMemories(query: String): Flow<List<MemoryItem>> {
        return memoriesMap.map { map ->
            map.values.filter { item ->
                (item.rawText?.contains(query, ignoreCase = true) == true) ||
                        (item.title?.contains(query, ignoreCase = true) == true)
            }.sortedByDescending { it.capturedAt }
        }
    }

    override fun getMemoryCount(): Flow<Int> {
        return memoriesMap.map { it.size }
    }

    override suspend fun markSourceExists(id: String, exists: Boolean) {
        memoriesMap.update { map ->
            val item = map[id] ?: return@update map
            map + (id to item.copy(sourceExists = exists))
        }
    }

    override suspend fun getMemoriesForReconciliation(
        sourceType: com.cayana.source.SourceType,
        cursorTimestamp: Long,
        limit: Int
    ): List<MemoryItem> {
        val eligible = memoriesMap.value.values
            .filter { it.sourceType == sourceType && it.sourceExists }
            .sortedByDescending { it.capturedAt }
        if (cursorTimestamp == Long.MAX_VALUE) {
            return eligible.take(limit)
        }
        val remaining = eligible.filter { it.capturedAt < cursorTimestamp }
        return if (remaining.isEmpty()) {
            eligible.take(limit)
        } else {
            remaining.take(limit)
        }
    }

    override suspend fun getMemoriesForCompoundReconciliation(
        sourceType: com.cayana.source.SourceType,
        cursorCapturedAt: Long,
        cursorId: String,
        limit: Int
    ): List<MemoryItem> {
        val eligible = memoriesMap.value.values
            .filter { it.sourceType == sourceType && it.sourceExists }
            .sortedWith(compareByDescending<MemoryItem> { it.capturedAt }.thenByDescending { it.id })

        val remaining = eligible.filter {
            it.capturedAt < cursorCapturedAt || (it.capturedAt == cursorCapturedAt && it.id < cursorId)
        }
        return remaining.take(limit)
    }

    override suspend fun clearAll() {
        memoriesMap.update { emptyMap() }
    }

    override suspend fun rebuildSearchIndex() {
        // No-op for in-memory fake
    }
}
