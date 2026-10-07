package com.cayana.cloud

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.cloud.auth.FakeCloudAuthManager
import com.cayana.cloud.client.CloudUpsertResponse
import com.cayana.cloud.client.FakeCayanaCloudClient
import com.cayana.cloud.crypto.CloudCryptoService
import com.cayana.cloud.crypto.EncryptedCloudRecord
import com.cayana.cloud.data.CloudMemorySyncMetadataDao
import com.cayana.cloud.data.CloudSyncOutboxDao
import com.cayana.cloud.data.CloudSyncStateDao
import com.cayana.cloud.data.CloudSyncStateEntity
import com.cayana.cloud.sync.CloudSyncManager
import com.cayana.cloud.sync.DefaultCloudSyncManager
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.Result
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MutationOrigin
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.source.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudOutboxIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var memoryDao: MemoryDao
    private lateinit var cloudSyncStateDao: CloudSyncStateDao
    private lateinit var cloudMemorySyncMetadataDao: CloudMemorySyncMetadataDao
    private lateinit var cloudSyncOutboxDao: CloudSyncOutboxDao
    private lateinit var memoryRepository: RoomMemoryRepository

    private lateinit var fakeCloudClient: FakeCayanaCloudClient
    private lateinit var fakeAuthManager: FakeCloudAuthManager
    private lateinit var recoveryKeyStorage: InMemoryRecoveryKeyStorage
    private lateinit var cloudSyncManager: CloudSyncManager

    private val rootKey = RecoveryKeyManager.generateRootKey()

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        memoryDao = database.memoryDao()
        cloudSyncStateDao = database.cloudSyncStateDao()
        cloudMemorySyncMetadataDao = database.cloudMemorySyncMetadataDao()
        cloudSyncOutboxDao = database.cloudSyncOutboxDao()

        memoryRepository = RoomMemoryRepository(
            memoryDao = memoryDao,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao(),
            dispatchers = AppDispatchers(),
            database = database,
            cloudSyncStateDao = cloudSyncStateDao,
            cloudMemorySyncMetadataDao = cloudMemorySyncMetadataDao,
            cloudSyncOutboxDao = cloudSyncOutboxDao
        )

        recoveryKeyStorage = InMemoryRecoveryKeyStorage()
        recoveryKeyStorage.saveRecoveryKey(rootKey)

        fakeCloudClient = FakeCayanaCloudClient()
        fakeAuthManager = FakeCloudAuthManager()

        cloudSyncManager = DefaultCloudSyncManager(
            cloudSyncStateDao = cloudSyncStateDao,
            cloudMemorySyncMetadataDao = cloudMemorySyncMetadataDao,
            cloudSyncOutboxDao = cloudSyncOutboxDao,
            memoryDao = memoryDao,
            memoryRepository = memoryRepository,
            cloudClient = fakeCloudClient,
            cloudAuthManager = fakeAuthManager,
            recoveryKeyStorage = recoveryKeyStorage,
            database = database
        )

        // Mark cloud sync as initialized & enabled
        cloudSyncStateDao.upsertSyncState(
            CloudSyncStateEntity(
                id = 1,
                isInitialized = true,
                isEnabled = true,
                lastPullSeq = 0L,
                lastSuccessfulSyncAt = 0L
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createSampleMemory(
        id: String = "mem-outbox-1",
        text: String = "Test content",
        sourceType: SourceType = SourceType.SCREENSHOT
    ): MemoryItem {
        return MemoryItem(
            id = id,
            sourceType = sourceType,
            createdAt = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis(),
            title = "Test Memory",
            rawText = text,
            normalizedText = text.lowercase(),
            sourceUri = "content://media/1",
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
    }

    @Test
    fun memorySaveAndOutboxAreAtomic() = runTest {
        val memory = createSampleMemory("mem-atomic-1", "Local memory save test")
        memoryRepository.saveMemory(memory, origin = MutationOrigin.LOCAL)

        // 1. Verify memory saved canonically
        val storedEntity = memoryDao.getMemoryById("mem-atomic-1")
        assertNotNull(storedEntity)
        assertEquals("mem-atomic-1", storedEntity?.id)

        // 2. Verify metadata created with revision 1
        val meta = cloudMemorySyncMetadataDao.getMetadata("mem-atomic-1")
        assertNotNull(meta)
        assertEquals(1L, meta?.revision)
        assertEquals(0L, meta?.lastSyncedRevision)

        // 3. Verify outbox enqueued atomically
        val outboxItems = cloudSyncOutboxDao.getPendingBatch(10)
        assertEquals(1, outboxItems.size)
        assertEquals("mem-atomic-1", outboxItems[0].memoryId)
        assertEquals(1L, outboxItems[0].revision)
        assertEquals("UPSERT", outboxItems[0].operation)
    }

    @Test
    fun memoryDeleteAndTombstoneAreAtomic() = runTest {
        val memory = createSampleMemory("mem-del-1", "Delete tombstone test")
        memoryRepository.saveMemory(memory, origin = MutationOrigin.LOCAL)
        cloudSyncOutboxDao.clearAll() // clear initial save outbox

        // Now perform canonical delete
        memoryRepository.deleteMemory("mem-del-1", origin = MutationOrigin.LOCAL)

        // 1. Canonical memory is deleted
        assertNull(memoryDao.getMemoryById("mem-del-1"))

        // 2. Metadata holds incremented revision 2
        val meta = cloudMemorySyncMetadataDao.getMetadata("mem-del-1")
        assertNotNull(meta)
        assertEquals(2L, meta?.revision)

        // 3. Outbox contains DELETE operation
        val outboxItems = cloudSyncOutboxDao.getPendingBatch(10)
        assertEquals(1, outboxItems.size)
        assertEquals("mem-del-1", outboxItems[0].memoryId)
        assertEquals(2L, outboxItems[0].revision)
        assertEquals("DELETE", outboxItems[0].operation)
    }

    @Test
    fun processDeathLeavesOutboxDurable() = runTest {
        val dbFile = File(context.cacheDir, "durable_outbox_test.db")
        if (dbFile.exists()) dbFile.delete()

        val durableDb = Room.databaseBuilder(context, CayanaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        durableDb.cloudSyncStateDao().upsertSyncState(
            CloudSyncStateEntity(id = 1, isInitialized = true, isEnabled = true)
        )

        val durableRepo = RoomMemoryRepository(
            memoryDao = durableDb.memoryDao(),
            searchDao = durableDb.searchDao(),
            searchIndexStateDao = durableDb.searchIndexStateDao(),
            dispatchers = AppDispatchers(),
            database = durableDb,
            cloudSyncStateDao = durableDb.cloudSyncStateDao(),
            cloudMemorySyncMetadataDao = durableDb.cloudMemorySyncMetadataDao(),
            cloudSyncOutboxDao = durableDb.cloudSyncOutboxDao()
        )

        val memory = createSampleMemory("mem-crash-1", "Data before sudden force stop")
        durableRepo.saveMemory(memory, origin = MutationOrigin.LOCAL)

        // Simulate crash: close database instance abruptly
        durableDb.close()

        // Restart process: re-open database from disk
        val reloadedDb = Room.databaseBuilder(context, CayanaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        val reloadedOutbox = reloadedDb.cloudSyncOutboxDao().getPendingBatch(10)
        assertEquals("Outbox must persist across process restarts", 1, reloadedOutbox.size)
        assertEquals("mem-crash-1", reloadedOutbox[0].memoryId)
        assertEquals(1L, reloadedOutbox[0].revision)

        reloadedDb.close()
        dbFile.delete()
    }

    @Test
    fun serverCommitThenCrashRetriesIdempotently() = runTest {
        val memory = createSampleMemory("mem-retry-1", "Idempotent commit retry test")
        memoryRepository.saveMemory(memory, origin = MutationOrigin.LOCAL)

        // First push: Server accepts the revision 1
        val syncResult1 = cloudSyncManager.syncOnce()
        assertTrue(syncResult1 is Result.Success)
        assertEquals(1, (syncResult1 as Result.Success).data.pushedCount)
        assertEquals(0, cloudSyncOutboxDao.getPendingBatch(10).size)

        // Now simulate client crashed before outbox removal in a prior run:
        // We re-insert the outbox item for revision 1 manually
        cloudSyncOutboxDao.enqueue(
            com.cayana.cloud.data.CloudSyncOutboxEntity(
                id = "retry-outbox-id",
                memoryId = "mem-retry-1",
                revision = 1L,
                operation = "UPSERT",
                createdAt = System.currentTimeMillis()
            )
        )

        // Retry sync: server returns IDEMPOTENT, outbox is cleared cleanly
        val syncResult2 = cloudSyncManager.syncOnce()
        assertTrue(syncResult2 is Result.Success)
        assertEquals(1, (syncResult2 as Result.Success).data.pushedCount)
        assertEquals(0, cloudSyncOutboxDao.getPendingBatch(10).size)

        // Verify only 1 record exists on the remote server
        assertEquals(1, fakeCloudClient.remoteRecords.size)
        assertEquals(1L, fakeCloudClient.remoteRecords["mem-retry-1"]?.revision)
    }

    @Test
    fun staleRevisionCannotOverwriteNewerRemoteRevision() = runTest {
        // Prepare remote server with revision 5
        val memoryId = "mem-stale-test"
        val newerRecord = CloudCryptoService.encryptMemory(
            payload = com.cayana.cloud.crypto.CloudMemoryPayloadV1(
                id = memoryId,
                sourceType = "SCREENSHOT",
                createdAt = 1000L,
                capturedAt = 1000L,
                title = "Newer remote memory v5"
            ),
            revision = 5L,
            cloudSyncKey = CloudCryptoService.deriveCloudSyncKey(rootKey)
        )
        fakeCloudClient.upsertRecord(newerRecord)

        // Client has older revision 4 in outbox
        cloudMemorySyncMetadataDao.upsertMetadata(
            com.cayana.cloud.data.CloudMemorySyncMetadataEntity(
                memoryId = memoryId,
                revision = 4L,
                lastSyncedRevision = 0L
            )
        )
        cloudSyncOutboxDao.enqueue(
            com.cayana.cloud.data.CloudSyncOutboxEntity(
                id = "stale-outbox-id",
                memoryId = memoryId,
                revision = 4L,
                operation = "UPSERT",
                createdAt = System.currentTimeMillis()
            )
        )

        // Run sync
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        // Remote server record must STILL be revision 5 (stale v4 rejected)
        val remoteRecord = fakeCloudClient.remoteRecords[memoryId]
        assertNotNull(remoteRecord)
        assertEquals("Server must NOT overwrite newer revision 5 with stale revision 4", 5L, remoteRecord?.revision)
    }

    @Test
    fun successfulAckRemovesOutbox() = runTest {
        // Enqueue 3 mutations
        val m1 = createSampleMemory("mem-batch-1", "Content 1")
        val m2 = createSampleMemory("mem-batch-2", "Content 2")
        val m3 = createSampleMemory("mem-batch-3", "Content 3")

        memoryRepository.saveMemory(m1, origin = MutationOrigin.LOCAL)
        memoryRepository.saveMemory(m2, origin = MutationOrigin.LOCAL)
        memoryRepository.saveMemory(m3, origin = MutationOrigin.LOCAL)
        // Delete m2 -> tombstone
        memoryRepository.deleteMemory(m2.id, origin = MutationOrigin.LOCAL)

        val pendingBefore = cloudSyncOutboxDao.getPendingBatch(10)
        assertTrue(pendingBefore.isNotEmpty())

        // Run sync
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        // Outbox must be completely empty after successful acknowledgment
        val pendingAfter = cloudSyncOutboxDao.getPendingBatch(10)
        assertEquals(0, pendingAfter.size)

        // Metadata lastSyncedRevision matches revision
        val meta1 = cloudMemorySyncMetadataDao.getMetadata("mem-batch-1")
        val meta2 = cloudMemorySyncMetadataDao.getMetadata("mem-batch-2")
        val meta3 = cloudMemorySyncMetadataDao.getMetadata("mem-batch-3")
        assertEquals(meta1?.revision, meta1?.lastSyncedRevision)
        assertEquals(meta2?.revision, meta2?.lastSyncedRevision)
        assertEquals(meta3?.revision, meta3?.lastSyncedRevision)
    }

    @Test
    fun sourceExistenceLocalChangeIncrementsRevisionAndEnqueuesOutbox() = runTest {
        // 1. Initial photo memory with sourceExists = true
        val photoMemory = createSampleMemory("photo-mem-1", "Photo content", SourceType.PHOTO)
        memoryRepository.saveMemory(photoMemory, origin = MutationOrigin.LOCAL)

        // Drain initial upload
        val sync1 = cloudSyncManager.syncOnce()
        assertTrue(sync1 is Result.Success)
        val meta1 = cloudMemorySyncMetadataDao.getMetadata(photoMemory.id)
        assertEquals(1L, meta1?.revision)

        // 2. Local reconciliation discovers source photo is missing on device
        memoryRepository.markSourceExists(photoMemory.id, false)

        // Verify local state updated
        val updatedLocal = memoryDao.getMemoryById(photoMemory.id)
        assertNotNull(updatedLocal)
        assertEquals(false, updatedLocal?.sourceExists)

        // Verify revision incremented (1 -> 2)
        val meta2 = cloudMemorySyncMetadataDao.getMetadata(photoMemory.id)
        assertEquals(2L, meta2?.revision)

        // Verify outbox has UPSERT with revision 2
        val pendingOutbox = cloudSyncOutboxDao.getPendingBatch(10)
        assertEquals(1, pendingOutbox.size)
        assertEquals(photoMemory.id, pendingOutbox[0].memoryId)
        assertEquals(2L, pendingOutbox[0].revision)
        assertEquals("UPSERT", pendingOutbox[0].operation)

        // 3. Sync to remote
        val sync2 = cloudSyncManager.syncOnce()
        assertTrue(sync2 is Result.Success)

        // Remote record received with revision 2
        val remoteRecord = fakeCloudClient.remoteRecords[photoMemory.id]
        assertNotNull(remoteRecord)
        assertEquals(2L, remoteRecord?.revision)

        // Decrypt remote payload and assert sourceExists is false
        val cloudSyncKey = CloudCryptoService.deriveCloudSyncKey(rootKey)
        val encryptedRecord = EncryptedCloudRecord(
            memoryId = remoteRecord!!.memory_id,
            revision = remoteRecord.revision,
            payloadVersion = remoteRecord.payload_version,
            nonceBase64 = remoteRecord.nonce,
            ciphertextBase64 = remoteRecord.ciphertext,
            isTombstone = remoteRecord.is_tombstone
        )
        val decrypted = CloudCryptoService.decryptMemory(encryptedRecord, cloudSyncKey)
        assertEquals(false, decrypted.sourceExists)
    }

    @Test
    fun processDeathDuringInitialCloudEnableCannotLoseMemoryUpload() = runTest {
        // Clear state to simulate uninitialized
        cloudSyncStateDao.clearAll()
        cloudMemorySyncMetadataDao.clearAll()
        cloudSyncOutboxDao.clearAll()

        // 3 canonical memories exist locally before cloud enable
        val m1 = createSampleMemory("mem-death-1", "Memory 1")
        val m2 = createSampleMemory("mem-death-2", "Memory 2")
        val m3 = createSampleMemory("mem-death-3", "Memory 3")
        memoryDao.insertOrUpdate(com.cayana.memory.data.MemoryEntity.fromDomain(m1))
        memoryDao.insertOrUpdate(com.cayana.memory.data.MemoryEntity.fromDomain(m2))
        memoryDao.insertOrUpdate(com.cayana.memory.data.MemoryEntity.fromDomain(m3))

        // Simulate crash/failure during atomic setup inside a transaction
        try {
            database.withTransaction {
                // Simulate partial writes
                cloudMemorySyncMetadataDao.upsertMetadata(
                    com.cayana.cloud.data.CloudMemorySyncMetadataEntity("mem-death-1", 1L, 0L)
                )
                // Crash before outbox enqueue
                throw RuntimeException("Simulated process death / crash")
            }
        } catch (_: Exception) {
            // Transaction rolled back
        }

        // Verify rollback: metadata is NOT half-baked
        val meta1AfterCrash = cloudMemorySyncMetadataDao.getMetadata("mem-death-1")
        assertNull("Rolled back transaction must leave no orphan metadata", meta1AfterCrash)
        assertEquals(0, cloudSyncOutboxDao.getPendingBatch(10).size)

        // Restart process / retry initializeAndEnable
        val initResult = cloudSyncManager.initializeAndEnable()
        assertTrue(initResult is Result.Success)

        // All 3 memories must now have metadata revision = 1 AND pending outbox entries
        val allPending = cloudSyncOutboxDao.getPendingBatch(10)
        assertEquals(3, allPending.size)
        val pendingIds = allPending.map { it.memoryId }.toSet()
        assertTrue(pendingIds.contains("mem-death-1"))
        assertTrue(pendingIds.contains("mem-death-2"))
        assertTrue(pendingIds.contains("mem-death-3"))

        assertNotNull(cloudMemorySyncMetadataDao.getMetadata("mem-death-1"))
        assertNotNull(cloudMemorySyncMetadataDao.getMetadata("mem-death-2"))
        assertNotNull(cloudMemorySyncMetadataDao.getMetadata("mem-death-3"))
    }
}
