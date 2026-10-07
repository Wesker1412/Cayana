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
    private val dispatchers: CoroutineDispatchers = com.cayana.core.common.AppDispatchers(),
    private val database: com.cayana.memory.data.CayanaDatabase? = null,
    private val cloudSyncStateDao: com.cayana.cloud.data.CloudSyncStateDao? = null,
    private val cloudMemorySyncMetadataDao: com.cayana.cloud.data.CloudMemorySyncMetadataDao? = null,
    private val cloudSyncOutboxDao: com.cayana.cloud.data.CloudSyncOutboxDao? = null
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
        origin: MutationOrigin,
        remoteRevision: Long?
    ): Unit = withContext(dispatchers.io) {
        searchIndexMutationMutex.withLock {
            searchIndexStateDao?.markDirty()
            indexNeedsRebuild = true

            val entity = MemoryEntity.fromDomain(item)

            val executeCanonicalAndOutbox: suspend () -> Unit = {
                memoryDao.insertOrUpdate(entity)

                if (origin == MutationOrigin.LOCAL) {
                    val isCloudInit = cloudSyncStateDao?.getSyncState()?.isInitialized == true
                    if (isCloudInit) {
                        val currentMeta = cloudMemorySyncMetadataDao?.getMetadata(item.id)
                        val newRevision = (currentMeta?.revision ?: 0L) + 1L
                        cloudMemorySyncMetadataDao?.upsertMetadata(
                            com.cayana.cloud.data.CloudMemorySyncMetadataEntity(
                                memoryId = item.id,
                                revision = newRevision,
                                lastSyncedRevision = currentMeta?.lastSyncedRevision ?: 0L
                            )
                        )
                        val outboxItem = com.cayana.cloud.data.CloudSyncOutboxEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            memoryId = item.id,
                            revision = newRevision,
                            operation = "UPSERT",
                            createdAt = System.currentTimeMillis()
                        )
                        cloudSyncOutboxDao?.enqueue(outboxItem)
                    }
                } else if (origin == MutationOrigin.CLOUD_SYNC) {
                    val rev = remoteRevision ?: 1L
                    cloudMemorySyncMetadataDao?.upsertMetadata(
                        com.cayana.cloud.data.CloudMemorySyncMetadataEntity(
                            memoryId = item.id,
                            revision = rev,
                            lastSyncedRevision = rev
                        )
                    )
                }
            }

            if (database != null) {
                database.withTransaction {
                    executeCanonicalAndOutbox()
                }
            } else {
                executeCanonicalAndOutbox()
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
        origin: MutationOrigin,
        remoteRevision: Long?
    ): Unit = withContext(dispatchers.io) {
        searchIndexMutationMutex.withLock {
            searchIndexStateDao?.markDirty()
            indexNeedsRebuild = true

            val executeDeleteAndOutbox: suspend () -> Unit = {
                memoryDao.deleteById(id)

                if (origin == MutationOrigin.LOCAL) {
                    val isCloudInit = cloudSyncStateDao?.getSyncState()?.isInitialized == true
                    if (isCloudInit) {
                        val currentMeta = cloudMemorySyncMetadataDao?.getMetadata(id)
                        val newRevision = (currentMeta?.revision ?: 0L) + 1L
                        cloudMemorySyncMetadataDao?.upsertMetadata(
                            com.cayana.cloud.data.CloudMemorySyncMetadataEntity(
                                memoryId = id,
                                revision = newRevision,
                                lastSyncedRevision = currentMeta?.lastSyncedRevision ?: 0L
                            )
                        )
                        val outboxItem = com.cayana.cloud.data.CloudSyncOutboxEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            memoryId = id,
                            revision = newRevision,
                            operation = "DELETE",
                            createdAt = System.currentTimeMillis()
                        )
                        cloudSyncOutboxDao?.enqueue(outboxItem)
                    }
                } else if (origin == MutationOrigin.CLOUD_SYNC) {
                    val rev = remoteRevision ?: 1L
                    cloudMemorySyncMetadataDao?.upsertMetadata(
                        com.cayana.cloud.data.CloudMemorySyncMetadataEntity(
                            memoryId = id,
                            revision = rev,
                            lastSyncedRevision = rev
                        )
                    )
                }
            }

            if (database != null) {
                database.withTransaction {
                    executeDeleteAndOutbox()
                }
            } else {
                executeDeleteAndOutbox()
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
