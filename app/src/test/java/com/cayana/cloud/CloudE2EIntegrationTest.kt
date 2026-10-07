package com.cayana.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.cloud.auth.FakeCloudAuthManager
import com.cayana.cloud.client.FakeCayanaCloudClient
import com.cayana.cloud.crypto.CloudCryptoService
import com.cayana.cloud.data.CloudMemorySyncMetadataDao
import com.cayana.cloud.data.CloudSyncOutboxDao
import com.cayana.cloud.data.CloudSyncStateDao
import com.cayana.cloud.sync.CloudSyncManager
import com.cayana.cloud.sync.DefaultCloudSyncManager
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.Result
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MutationOrigin
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.search.data.SearchDao
import com.cayana.source.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudE2EIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var memoryDao: MemoryDao
    private lateinit var searchDao: SearchDao
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
        searchDao = database.searchDao()
        cloudSyncStateDao = database.cloudSyncStateDao()
        cloudMemorySyncMetadataDao = database.cloudMemorySyncMetadataDao()
        cloudSyncOutboxDao = database.cloudSyncOutboxDao()

        memoryRepository = RoomMemoryRepository(
            memoryDao = memoryDao,
            searchDao = searchDao,
            searchIndexStateDao = database.searchIndexStateDao(),
            dispatchers = AppDispatchers(),
            database = database,
            cloudSyncStateDao = cloudSyncStateDao,
            cloudMemorySyncMetadataDao = cloudMemorySyncMetadataDao,
            cloudSyncOutboxDao = cloudSyncOutboxDao
        )

        recoveryKeyStorage = InMemoryRecoveryKeyStorage()
        recoveryKeyStorage.saveRecoveryKey(rootKey)

        fakeAuthManager = FakeCloudAuthManager(
            initialStatus = com.cayana.cloud.auth.CloudAuthStatus.AUTHENTICATED,
            simulatedUserId = "tenant-e2e"
        )
        fakeCloudClient = FakeCayanaCloudClient(simulatedOwnerId = "tenant-e2e")

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fullCloudSyncLifecycleWithPrivacyAndRegressionVerification() = runTest {
        val secretMarker = "CAYANA_STAGE7_SECRET_MARKER"

        // Step 1: Prepare memories across all 5 types
        val screenshotMem = MemoryItem(
            id = "mem-screenshot-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Bank Statement",
            rawText = "Confidential financial record containing $secretMarker for Stage 7",
            normalizedText = "confidential financial record containing $secretMarker for stage 7",
            sourceUri = "content://media/images/screenshot1",
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        val photoMem = MemoryItem(
            id = "mem-photo-1",
            sourceType = SourceType.PHOTO,
            createdAt = 1100L,
            capturedAt = 1100L,
            title = "Receipt",
            rawText = "Coffee Shop Receipt $120",
            normalizedText = "coffee shop receipt $120",
            sourceUri = "content://media/images/photo1",
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        val recordingMem = MemoryItem(
            id = "mem-recording-1",
            sourceType = SourceType.RECORDING,
            createdAt = 1200L,
            capturedAt = 1200L,
            title = "Voice Memo",
            rawText = "Discuss project milestones with team",
            normalizedText = "discuss project milestones with team",
            sourceUri = "content://media/audio/recording1",
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        val sharedTextMem = MemoryItem(
            id = "mem-shared-text-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1300L,
            capturedAt = 1300L,
            title = "Shared Recipe",
            rawText = "Grandma apple pie recipe ingredients",
            normalizedText = "grandma apple pie recipe ingredients",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = false,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        val sharedUrlMem = MemoryItem(
            id = "mem-shared-url-1",
            sourceType = SourceType.SHARED_URL,
            createdAt = 1400L,
            capturedAt = 1400L,
            title = "Interesting Article",
            rawText = "Artificial intelligence research paper",
            normalizedText = "artificial intelligence research paper",
            sourceUri = null,
            sourceUrl = "https://example.com/article/1",
            sourceExists = false,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )

        // Save locally before cloud enable
        memoryRepository.saveMemory(screenshotMem, origin = MutationOrigin.LOCAL)
        memoryRepository.saveMemory(photoMem, origin = MutationOrigin.LOCAL)
        memoryRepository.saveMemory(recordingMem, origin = MutationOrigin.LOCAL)
        memoryRepository.saveMemory(sharedTextMem, origin = MutationOrigin.LOCAL)
        memoryRepository.saveMemory(sharedUrlMem, origin = MutationOrigin.LOCAL)

        assertEquals(5, memoryDao.getAllMemoriesDirect().size)

        // Step 2: Enable Cayana Cloud (performs initial scan and outbox enqueue)
        val enableResult = cloudSyncManager.initializeAndEnable(context)
        assertTrue(enableResult is Result.Success)

        // Verify outbox has 5 pending UPSERTs
        val pendingOutbox = cloudSyncOutboxDao.getPendingBatch(50)
        assertEquals(5, pendingOutbox.size)

        // Step 3: Run sync to push all 5 memories to Cloud
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)
        assertEquals(5, (syncResult as Result.Success).data.pushedCount)
        assertEquals(0, cloudSyncOutboxDao.getPendingBatch(50).size)

        // Step 4: Strict Privacy Verification on Raw Cloud Database
        assertEquals(5, fakeCloudClient.remoteRecords.size)
        for ((memId, remoteRecord) in fakeCloudClient.remoteRecords) {
            // Check that raw ciphertext base64 does not contain secret marker
            assertFalse(
                "Remote ciphertext base64 must NEVER contain secret marker!",
                remoteRecord.ciphertext.contains(secretMarker)
            )

            val rawBytes = Base64.getDecoder().decode(remoteRecord.ciphertext)
            val asciiString = String(rawBytes, Charsets.ISO_8859_1)
            assertFalse(
                "Decoded raw ciphertext bytes must NEVER contain secret marker!",
                asciiString.contains(secretMarker)
            )

            // Nonce must not contain marker
            assertFalse(remoteRecord.nonce.contains(secretMarker))
            // Only anonymous metadata exists
            assertFalse(remoteRecord.owner_id?.contains(secretMarker) == true)
        }

        // Step 5: Update a Memory (revision increments, remote row updates)
        val updatedPhoto = photoMem.copy(title = "Updated Coffee Shop Receipt $150", rawText = "Coffee Shop Receipt $150 with tip")
        memoryRepository.saveMemory(updatedPhoto, origin = MutationOrigin.LOCAL)

        val updatedMeta = cloudMemorySyncMetadataDao.getMetadata(photoMem.id)
        assertEquals(2L, updatedMeta?.revision)

        val pushUpdateResult = cloudSyncManager.syncOnce()
        assertTrue(pushUpdateResult is Result.Success)
        assertEquals(1, (pushUpdateResult as Result.Success).data.pushedCount)
        assertEquals(2L, fakeCloudClient.remoteRecords[photoMem.id]?.revision)

        // Step 6: Delete a Memory (tombstone uploaded, media unchanged)
        val dummyMediaFile = File(context.cacheDir, "test_screenshot_preserved.png")
        dummyMediaFile.writeText("preserved bytes")
        assertTrue(dummyMediaFile.exists())

        memoryRepository.deleteMemory(screenshotMem.id, origin = MutationOrigin.LOCAL)
        assertNull(memoryDao.getMemoryById(screenshotMem.id))

        val tombstoneMeta = cloudMemorySyncMetadataDao.getMetadata(screenshotMem.id)
        assertEquals(2L, tombstoneMeta?.revision)

        val pushDeleteResult = cloudSyncManager.syncOnce()
        assertTrue(pushDeleteResult is Result.Success)
        val remoteScreenshot = fakeCloudClient.remoteRecords[screenshotMem.id]
        assertNotNull(remoteScreenshot)
        assertTrue(remoteScreenshot!!.is_tombstone)
        assertEquals(2L, remoteScreenshot.revision)
        // Original media untouched
        assertTrue("Media must remain intact on delete", dummyMediaFile.exists())
        dummyMediaFile.delete()

        // Step 7: Offline Save & Outbox Durability
        fakeCloudClient.shouldFailUpsert = true // Simulate offline / network failure
        val offlineMem = MemoryItem(
            id = "mem-offline-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 2000L,
            capturedAt = 2000L,
            title = "Offline Memory",
            rawText = "Created while offline on airplane",
            normalizedText = "created while offline on airplane",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = false,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        // Local save succeeds despite offline
        memoryRepository.saveMemory(offlineMem, origin = MutationOrigin.LOCAL)
        assertNotNull(memoryDao.getMemoryById(offlineMem.id))

        // Outbox contains the offline mutation
        assertEquals(1, cloudSyncOutboxDao.getPendingBatch(10).size)

        // Sync fails gracefully without rolling back local memory
        val offlineSync = cloudSyncManager.syncOnce()
        assertTrue(offlineSync is Result.Error)
        assertNotNull(memoryDao.getMemoryById(offlineMem.id))
        assertEquals(1, cloudSyncOutboxDao.getPendingBatch(10).size)

        // Step 8: Network Restored -> Outbox Drains
        fakeCloudClient.shouldFailUpsert = false
        val onlineSync = cloudSyncManager.syncOnce()
        assertTrue(onlineSync is Result.Success)
        assertEquals(1, (onlineSync as Result.Success).data.pushedCount)
        assertEquals(0, cloudSyncOutboxDao.getPendingBatch(10).size)
        assertNotNull(fakeCloudClient.remoteRecords["mem-offline-1"])

        // Step 9: Search & Calendar Regression Verification
        val calendarDao = database.calendarActionDao()
        val calendarCountBefore = calendarDao.getAll().size

        val pulledSearchKeyword = "DeepSpaceTelescopeOpticalSensors"
        val remoteRecord = CloudCryptoService.encryptMemory(
            payload = com.cayana.cloud.crypto.CloudMemoryPayloadV1(
                id = "mem-remote-search-test",
                sourceType = "SCREENSHOT",
                createdAt = 3000L,
                capturedAt = 3000L,
                title = "Astrophysics Memo tomorrow 3pm",
                rawText = "Conference on $pulledSearchKeyword at auditorium"
            ),
            revision = 1L,
            cloudSyncKey = CloudCryptoService.deriveCloudSyncKey(rootKey)
        )
        fakeCloudClient.upsertRecord(remoteRecord)

        val pullSync = cloudSyncManager.syncOnce()
        assertTrue(pullSync is Result.Success)

        // Search index updated
        val searchHits = searchDao.searchMemoriesMatchFlow(pulledSearchKeyword).first()
        assertEquals(1, searchHits.size)
        assertEquals("mem-remote-search-test", searchHits[0].id)

        // Calendar count unchanged
        val calendarCountAfter = calendarDao.getAll().size
        assertEquals("Calendar count must not change on cloud pull", calendarCountBefore, calendarCountAfter)
    }
}
