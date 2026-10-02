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

    override suspend fun saveMemory(item: MemoryItem) {
        memoriesMap.update { it + (item.id to item) }
    }

    override suspend fun deleteMemory(id: String) {
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

    override suspend fun clearAll() {
        memoriesMap.update { emptyMap() }
    }
}
