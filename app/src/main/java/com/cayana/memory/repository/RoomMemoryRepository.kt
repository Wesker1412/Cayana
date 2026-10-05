package com.cayana.memory.repository

import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.data.Converters
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.model.MemoryItem
import com.cayana.search.MemorySearchDocumentBuilder
import com.cayana.search.data.SearchDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomMemoryRepository(
    private val memoryDao: MemoryDao,
    private val searchDao: SearchDao? = null,
    private val dispatchers: CoroutineDispatchers = com.cayana.core.common.AppDispatchers()
) : MemoryRepository {

    @Volatile
    private var indexNeedsRebuild: Boolean = false

    override fun isIndexRebuildNeeded(): Boolean = indexNeedsRebuild

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

    override suspend fun saveMemory(item: MemoryItem): Unit = withContext(dispatchers.io) {
        val entity = MemoryEntity.fromDomain(item)
        memoryDao.insertOrUpdate(entity)

        // Update derived search index safely without failing memory ingestion
        try {
            searchDao?.let { sDao ->
                val ftsEntity = MemorySearchDocumentBuilder.buildDocument(item)
                sDao.insertFts(ftsEntity)
            }
        } catch (e: Exception) {
            CayanaLogger.w("SearchIndex", "Failed to update FTS index for memory: ${e.message}")
            indexNeedsRebuild = true
        }
    }

    override suspend fun deleteMemory(id: String): Unit = withContext(dispatchers.io) {
        memoryDao.deleteById(id)
        try {
            searchDao?.deleteFtsByMemoryId(id)
        } catch (e: Exception) {
            CayanaLogger.w("SearchIndex", "Failed to delete FTS entry: ${e.message}")
            indexNeedsRebuild = true
        }
    }

    override fun searchMemories(query: String): Flow<List<MemoryItem>> {
        return memoryDao.searchMemoriesFlow(query)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override suspend fun rebuildSearchIndex(): Unit = withContext(dispatchers.io) {
        val sDao = searchDao ?: return@withContext
        try {
            sDao.clearFts()
            val allEntities = memoryDao.getAllMemoriesDirect()
            val ftsList = allEntities.map { entity ->
                val metadata = Converters.parseMetadata(entity.metadataJson)
                MemorySearchDocumentBuilder.buildDocument(entity, metadata)
            }
            sDao.insertAllFts(ftsList)
            indexNeedsRebuild = false
        } catch (e: Exception) {
            CayanaLogger.w("SearchIndex", "Failed to rebuild search index: ${e.message}")
            indexNeedsRebuild = true
        }
        Unit
    }

    override fun getMemoryCount(): Flow<Int> {
        return memoryDao.getCountFlow()
            .flowOn(dispatchers.io)
    }

    override suspend fun markSourceExists(id: String, exists: Boolean) = withContext(dispatchers.io) {
        memoryDao.updateSourceExists(id, exists)
    }

    override suspend fun getMemoriesForReconciliation(
        sourceType: com.cayana.source.SourceType,
        cursorTimestamp: Long,
        limit: Int
    ): List<MemoryItem> = withContext(dispatchers.io) {
        val entities = if (cursorTimestamp == Long.MAX_VALUE) {
            memoryDao.getInitialMemoriesForReconciliation(sourceType.name, limit)
        } else {
            val next = memoryDao.getExistingMemoriesForReconciliation(sourceType.name, cursorTimestamp, limit)
            if (next.isEmpty()) {
                memoryDao.getInitialMemoriesForReconciliation(sourceType.name, limit)
            } else {
                next
            }
        }
        entities.map { it.toDomain() }
    }

    override suspend fun getMemoriesForCompoundReconciliation(
        sourceType: com.cayana.source.SourceType,
        cursorCapturedAt: Long,
        cursorId: String,
        limit: Int
    ): List<MemoryItem> = withContext(dispatchers.io) {
        val entities = memoryDao.getMemoriesForCompoundReconciliation(
            sourceType = sourceType.name,
            cursorCapturedAt = cursorCapturedAt,
            cursorId = cursorId,
            limit = limit
        )
        entities.map { it.toDomain() }
    }

    override suspend fun clearAll(): Unit = withContext(dispatchers.io) {
        memoryDao.clearAll()
        try {
            searchDao?.clearFts()
        } catch (e: Exception) {
            CayanaLogger.w("SearchIndex", "Failed to clear FTS index: ${e.message}")
        }
        Unit
    }
}
