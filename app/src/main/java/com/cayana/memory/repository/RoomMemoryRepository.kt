package com.cayana.memory.repository

import androidx.room.withTransaction
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.data.Converters
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.model.MemoryItem
import com.cayana.search.MemorySearchDocumentBuilder
import com.cayana.search.data.SearchDao
import com.cayana.search.data.SearchIndexStateDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomMemoryRepository(
    private val memoryDao: MemoryDao,
    private val searchDao: SearchDao? = null,
    private val searchIndexStateDao: SearchIndexStateDao? = null,
    private val searchIndexVersionStorage: com.cayana.search.data.SearchIndexVersionStorage? = null,
    private val dispatchers: CoroutineDispatchers = com.cayana.core.common.AppDispatchers(),
    private val database: com.cayana.memory.data.CayanaDatabase? = null
) : MemoryRepository {

    private val searchIndexMutationMutex = Mutex()

    @Volatile
    private var indexNeedsRebuild: Boolean = false

    override fun isIndexRebuildNeeded(): Boolean {
        return searchIndexStateDao?.let { dao ->
            runBlocking { dao.isDirty() ?: false }
        } ?: indexNeedsRebuild
    }

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

    override suspend fun saveMemory(
        item: MemoryItem,
        origin: MutationOrigin
    ): Unit = withContext(dispatchers.io) {
        searchIndexMutationMutex.withLock {
            searchIndexStateDao?.markDirty()
            indexNeedsRebuild = true

            val entity = MemoryEntity.fromDomain(item)

            if (database != null) {
                database.withTransaction {
                    memoryDao.insertOrUpdate(entity)
                }
            } else {
                memoryDao.insertOrUpdate(entity)
            }

            // Update derived search index safely with replaceFts
            try {
                searchDao?.let { sDao ->
                    val ftsEntity = MemorySearchDocumentBuilder.buildDocument(item)
                    sDao.replaceFts(ftsEntity)
                }
                searchIndexStateDao?.clearDirtyIfNoPending()
                indexNeedsRebuild = searchIndexStateDao?.isDirty() ?: false
            } catch (e: Exception) {
                CayanaLogger.w("SearchIndex", "Failed to replace FTS: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun deleteMemory(
        id: String,
        origin: MutationOrigin
    ): Unit = withContext(dispatchers.io) {
        searchIndexMutationMutex.withLock {
            searchIndexStateDao?.markDirty()
            indexNeedsRebuild = true

            if (database != null) {
                database.withTransaction {
                    memoryDao.deleteById(id)
                }
            } else {
                memoryDao.deleteById(id)
            }

            try {
                searchDao?.deleteFtsByMemoryId(id)
                searchIndexStateDao?.clearDirtyIfNoPending()
                indexNeedsRebuild = searchIndexStateDao?.isDirty() ?: false
            } catch (e: Exception) {
                CayanaLogger.w("SearchIndex", "Failed to delete FTS entry: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun searchMemories(query: String): Flow<List<MemoryItem>> {
        return memoryDao.searchMemoriesFlow(query)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override suspend fun searchMemoriesBounded(query: String, limit: Int): List<MemoryItem> = withContext(dispatchers.io) {
        memoryDao.searchMemoriesBounded(query, limit).map { it.toDomain() }
    }

    override suspend fun rebuildSearchIndex(): Unit = withContext(dispatchers.io) {
        val sDao = searchDao ?: return@withContext
        searchIndexMutationMutex.withLock {
            try {
                sDao.clearFts()
                val allEntities = memoryDao.getAllMemoriesDirect()
                // Bounded batches of 50
                allEntities.chunked(50).forEach { batch ->
                    val ftsList = batch.map { entity ->
                        val metadata = Converters.parseMetadata(entity.metadataJson)
                        MemorySearchDocumentBuilder.buildDocument(entity, metadata)
                    }
                    sDao.insertAllFts(ftsList)
                }
                searchIndexStateDao?.forceClearDirty()
                searchIndexVersionStorage?.setIndexFormatVersion(com.cayana.retrieval.RetrievalPolicy.CURRENT_INDEX_FORMAT_VERSION)
                indexNeedsRebuild = false
            } catch (e: Exception) {
                CayanaLogger.w("SearchIndex", "Failed to rebuild search index: ${e.javaClass.simpleName}")
                indexNeedsRebuild = true
                throw e
            }
        }
        Unit
    }

    override fun getMemoryCount(): Flow<Int> {
        return memoryDao.getCountFlow()
            .flowOn(dispatchers.io)
    }

    override suspend fun markSourceExists(id: String, exists: Boolean) = withContext(dispatchers.io) {
        val existing = memoryDao.getMemoryById(id)?.toDomain()
        if (existing != null && existing.sourceExists != exists) {
            val updated = existing.copy(sourceExists = exists)
            saveMemory(updated, origin = MutationOrigin.LOCAL)
        }
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
        searchIndexMutationMutex.withLock {
            memoryDao.clearAll()
            try {
                searchDao?.clearFts()
                searchIndexStateDao?.forceClearDirty()
                indexNeedsRebuild = false
            } catch (e: Exception) {
                CayanaLogger.w("SearchIndex", "Failed to clear FTS index: ${e.javaClass.simpleName}")
            }
        }
        Unit
    }
}
