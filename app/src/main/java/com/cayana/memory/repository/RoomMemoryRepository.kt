package com.cayana.memory.repository

import com.cayana.core.common.CoroutineDispatchers
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomMemoryRepository(
    private val memoryDao: MemoryDao,
    private val dispatchers: CoroutineDispatchers
) : MemoryRepository {

    override fun getAllMemories(): Flow<List<MemoryItem>> {
        return memoryDao.getAllMemoriesFlow()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun getMemoryById(id: String): Flow<MemoryItem?> {
        return memoryDao.getMemoryByIdFlow(id)
            .map { it?.toDomain() }
            .flowOn(dispatchers.io)
    }

    override suspend fun getMemoryBySourceUri(sourceUri: String): MemoryItem? = withContext(dispatchers.io) {
        memoryDao.getMemoryBySourceUri(sourceUri)?.toDomain()
    }

    override suspend fun saveMemory(item: MemoryItem) = withContext(dispatchers.io) {
        val entity = MemoryEntity.fromDomain(item)
        memoryDao.insertOrUpdate(entity)
    }

    override suspend fun deleteMemory(id: String) = withContext(dispatchers.io) {
        memoryDao.deleteById(id)
    }

    override fun searchMemories(query: String): Flow<List<MemoryItem>> {
        return memoryDao.searchMemoriesFlow(query)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun getMemoryCount(): Flow<Int> {
        return memoryDao.getCountFlow()
            .flowOn(dispatchers.io)
    }

    override suspend fun markSourceExists(id: String, exists: Boolean) = withContext(dispatchers.io) {
        memoryDao.updateSourceExists(id, exists)
    }

    override suspend fun clearAll() = withContext(dispatchers.io) {
        memoryDao.clearAll()
    }
}
