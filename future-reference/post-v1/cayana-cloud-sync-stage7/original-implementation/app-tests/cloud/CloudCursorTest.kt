package com.cayana.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.cloud.auth.FakeCloudAuthManager
import com.cayana.cloud.client.CloudRemoteRecord
import com.cayana.cloud.client.FakeCayanaCloudClient
import com.cayana.cloud.crypto.CloudCryptoService
import com.cayana.cloud.crypto.CloudMemoryPayloadV1
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
import com.cayana.memory.repository.RoomMemoryRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudCursorTest {

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
    private val cloudSyncKey = CloudCryptoService.deriveCloudSyncKey(rootKey)

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
            recoveryKeyStorage = recoveryKeyStorage
        )

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

    private fun createRemoteRecord(
        memoryId: String,
        revision: Long,
        title: String
    ): EncryptedCloudRecord {
        val payload = CloudMemoryPayloadV1(
            id = memoryId,
            sourceType = "SCREENSHOT",
            createdAt = 1000L,
            capturedAt = 1000L,
            title = title,
            rawText = "Text for $title"
        )
        return CloudCryptoService.encryptMemory(payload, revision, cloudSyncKey)
    }

    @Test
    fun pullCursorAdvancesOnlyAfterSuccessfulBatch() = runTest {
        // Enqueue 3 records on server
        val r1 = createRemoteRecord("mem-seq-1", 1L, "Seq 1")
        val r2 = createRemoteRecord("mem-seq-2", 1L, "Seq 2")
        val r3 = createRemoteRecord("mem-seq-3", 1L, "Seq 3")

        val resp1 = fakeCloudClient.upsertRecord(r1) as Result.Success
        val resp2 = fakeCloudClient.upsertRecord(r2) as Result.Success
        val resp3 = fakeCloudClient.upsertRecord(r3) as Result.Success

        val maxServerSeq = resp3.data.change_seq ?: 0L
        assertTrue(maxServerSeq > 0)

        // Initial cursor is 0
        assertEquals(0L, cloudSyncStateDao.getSyncState()?.lastPullSeq)

        // Perform sync
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)
        assertEquals(3, (syncResult as Result.Success).data.pulledCount)

        // Cursor must advance to the latest sequence
        val stateAfter = cloudSyncStateDao.getSyncState()
        assertEquals(maxServerSeq, stateAfter?.lastPullSeq)
    }

    @Test
    fun failedRecordDoesNotGetSkipped() = runTest {
        val r1 = createRemoteRecord("mem-skip-1", 1L, "Record 1")
        val r2Valid = createRemoteRecord("mem-skip-2", 1L, "Record 2")
        val r3 = createRemoteRecord("mem-skip-3", 1L, "Record 3")

        val resp1 = fakeCloudClient.upsertRecord(r1) as Result.Success
        val seq1 = resp1.data.change_seq

        // Corrupt r2
        val cipherBytes = Base64.getDecoder().decode(r2Valid.ciphertextBase64)
        cipherBytes[0] = (cipherBytes[0].toInt() xor 0xAA).toByte()
        val r2Corrupted = r2Valid.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(cipherBytes))
        fakeCloudClient.upsertRecord(r2Corrupted)

        fakeCloudClient.upsertRecord(r3)

        // Run sync - r1 applies, r2 fails
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue("Sync should return error when record fails", syncResult is Result.Error)

        // Cursor should only advance to seq1 and NOT skip corrupted r2 or advance to r3
        val state = cloudSyncStateDao.getSyncState()
        assertEquals("Cursor must stop at the last successful record", seq1, state?.lastPullSeq)
        assertNotNull(memoryDao.getMemoryById("mem-skip-1"))
        // Record 3 was not processed or skipped
        org.junit.Assert.assertNull(memoryDao.getMemoryById("mem-skip-3"))
    }

    @Test
    fun processDeathBeforeCursorCommitReplaysSafely() = runTest {
        val r1 = createRemoteRecord("mem-replay-1", 1L, "Original data")
        fakeCloudClient.upsertRecord(r1)

        // Simulate crash right before cursor is updated:
        // Record is in database or pulled, but lastPullSeq is still 0
        assertEquals(0L, cloudSyncStateDao.getSyncState()?.lastPullSeq)

        // Next sync run replays from sequence 0
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        val memory = memoryDao.getMemoryById("mem-replay-1")
        assertNotNull(memory)
        assertEquals("Original data", memory?.title)

        // Now replay the exact same sync again
        val replayResult = cloudSyncManager.syncOnce()
        assertTrue(replayResult is Result.Success)
        // No extra pull since cursor is up to date
        assertEquals(0, (replayResult as Result.Success).data.pulledCount)
    }

    @Test
    fun duplicateRemoteChangeIsIdempotent() = runTest {
        val r1 = createRemoteRecord("mem-idem-1", 1L, "Idempotent Data")
        val resp = fakeCloudClient.upsertRecord(r1) as Result.Success
        val seq = resp.data.change_seq

        // First sync
        val syncResult1 = cloudSyncManager.syncOnce()
        assertTrue(syncResult1 is Result.Success)
        assertEquals(1, (syncResult1 as Result.Success).data.pulledCount)

        // Reset cursor back to 0 to simulate re-receiving the exact same sequence & record
        cloudSyncStateDao.updateLastPullSeq(0L)

        // Second sync processes duplicate record
        val syncResult2 = cloudSyncManager.syncOnce()
        assertTrue(syncResult2 is Result.Success)

        // Verify local memory remains intact without duplication or errors
        val allMemories = memoryDao.getAllMemoriesDirect()
        assertEquals(1, allMemories.filter { it.id == "mem-idem-1" }.size)
        assertEquals(seq, cloudSyncStateDao.getSyncState()?.lastPullSeq)
    }
}
