package com.cayana.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.cloud.auth.FakeCloudAuthManager
import com.cayana.cloud.client.CloudRemoteRecord
import com.cayana.cloud.client.FakeCayanaCloudClient
import com.cayana.cloud.crypto.CloudCryptoService
import com.cayana.cloud.crypto.CloudMemoryPayloadV1
import com.cayana.cloud.crypto.EncryptedCloudRecord
import com.cayana.cloud.crypto.toDomain
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
class CloudPullIntegrationTest {

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
    private val cloudSyncKey = CloudCryptoService.deriveCloudSyncKey(rootKey)

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

    private fun createRemoteMemoryRecord(
        memoryId: String,
        revision: Long,
        title: String,
        text: String
    ): EncryptedCloudRecord {
        val payload = CloudMemoryPayloadV1(
            id = memoryId,
            sourceType = "SCREENSHOT",
            createdAt = 1728000000000L,
            capturedAt = 1728000000000L,
            title = title,
            rawText = text,
            normalizedText = text.lowercase(),
            sourceUri = "content://media/images/1",
            sourceUrl = null,
            sourceExists = true,
            metadataJson = "{}",
            entitiesJson = "[]",
            eventCandidatesJson = "[]",
            processingState = "COMPLETED"
        )
        return CloudCryptoService.encryptMemory(payload, revision, cloudSyncKey)
    }

    @Test
    fun remoteNewerMemoryAppliesLocally() = runTest {
        val memoryId = "mem-pull-1"
        val record = createRemoteMemoryRecord(memoryId, revision = 2L, title = "New Remote Title", text = "Updated remote body")
        fakeCloudClient.upsertRecord(record)

        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)
        assertEquals(1, (syncResult as Result.Success).data.pulledCount)

        val localEntity = memoryDao.getMemoryById(memoryId)
        assertNotNull(localEntity)
        assertEquals("New Remote Title", localEntity?.title)
        assertEquals("Updated remote body", localEntity?.rawText)

        val meta = cloudMemorySyncMetadataDao.getMetadata(memoryId)
        assertNotNull(meta)
        assertEquals(2L, meta?.revision)
        assertEquals(2L, meta?.lastSyncedRevision)
    }

    @Test
    fun remoteOlderMemoryIgnored() = runTest {
        val memoryId = "mem-conflict-older"
        // 1. Local already at revision 3
        val localMemory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Local Newer Title",
            rawText = "Local newer text",
            normalizedText = "local newer text",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        memoryRepository.saveMemory(localMemory, origin = MutationOrigin.LOCAL)
        // Bump local metadata revision to 3
        cloudMemorySyncMetadataDao.upsertMetadata(
            com.cayana.cloud.data.CloudMemorySyncMetadataEntity(memoryId, revision = 3L, lastSyncedRevision = 0L)
        )
        cloudSyncOutboxDao.clearAll()

        // 2. Remote provides older revision 2
        val olderRecord = createRemoteMemoryRecord(memoryId, revision = 2L, title = "Old Stale Remote", text = "Stale body")
        fakeCloudClient.upsertRecord(olderRecord)

        // Sync pull
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        // Local must NOT be overwritten by older remote
        val localEntity = memoryDao.getMemoryById(memoryId)
        assertEquals("Local Newer Title", localEntity?.title)
        assertEquals("Local newer text", localEntity?.rawText)
    }

    @Test
    fun remoteApplyDoesNotCreateNewOutbox() = runTest {
        val memoryId = "mem-no-echo"
        val record = createRemoteMemoryRecord(memoryId, revision = 1L, title = "No Echo Title", text = "Prevent echo loops")
        fakeCloudClient.upsertRecord(record)

        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        // Local memory applied
        assertNotNull(memoryDao.getMemoryById(memoryId))

        // Outbox MUST be empty to prevent infinite echo loops
        val pendingOutbox = cloudSyncOutboxDao.getPendingBatch(10)
        assertEquals("Pull apply must NOT create outbox items", 0, pendingOutbox.size)
    }

    @Test
    fun remoteApplyUpdatesSearch() = runTest {
        val memoryId = "mem-search-pull"
        val uniqueKeyword = "QuantumSuperconductingMagnet2026"
        val record = createRemoteMemoryRecord(memoryId, revision = 1L, title = "Physics Lecture", text = "Notes on $uniqueKeyword")
        fakeCloudClient.upsertRecord(record)

        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        // FTS search index should contain the newly pulled memory
        val searchResults = searchDao.searchMemoriesMatchFlow(uniqueKeyword).first()
        assertEquals(1, searchResults.size)
        assertEquals(memoryId, searchResults[0].id)
    }

    @Test
    fun remoteMemoryDoesNotTriggerCalendarAutomation() = runTest {
        val calendarDao = database.calendarActionDao()
        val calendarActionsBefore = calendarDao.getAll().size

        // Remote record with calendar text
        val memoryId = "mem-cal-no-trigger"
        val record = createRemoteMemoryRecord(memoryId, revision = 1L, title = "Flight to Tokyo tomorrow at 10am", text = "Flight TK123 departure 10:00")
        fakeCloudClient.upsertRecord(record)

        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        // Verify calendar actions count has NOT changed
        val calendarActionsAfter = calendarDao.getAll().size
        assertEquals("Cloud pull must not trigger calendar automation", calendarActionsBefore, calendarActionsAfter)
    }

    @Test
    fun remoteTombstoneDeletesMemoryOnly() = runTest {
        val memoryId = "mem-tombstone-test"
        // 1. Create local memory and an associated calendar action
        val localMemory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Memory with event",
            rawText = "Event info",
            normalizedText = "event info",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        memoryRepository.saveMemory(localMemory, origin = MutationOrigin.LOCAL)
        cloudSyncOutboxDao.clearAll()

        val calendarDao = database.calendarActionDao()
        val calendarAction = CalendarActionEntity(
            id = "cal-123",
            memoryId = memoryId,
            calendarId = 1L,
            calendarEventId = 999L,
            actionType = "CREATE",
            createdAt = System.currentTimeMillis(),
            status = "CONFIRMED",
            title = "Meeting",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 3600000L
        )
        calendarDao.insert(calendarAction)

        // 2. Remote sends authenticated tombstone with revision 2
        val tombstoneRecord = CloudCryptoService.encryptTombstone(memoryId, revision = 2L, cloudSyncKey = cloudSyncKey)
        fakeCloudClient.upsertRecord(tombstoneRecord)

        // Pull
        val syncResult = cloudSyncManager.syncOnce()
        assertTrue(syncResult is Result.Success)

        // 3. Local canonical memory is deleted
        assertNull(memoryDao.getMemoryById(memoryId))

        // 4. CalendarAction is NOT deleted (Stage 5 invariant preserved)
        val remainingActions = calendarDao.getActionsForMemory(memoryId)
        assertEquals(1, remainingActions.size)
        assertEquals(999L, remainingActions[0].calendarEventId)
    }

    @Test
    fun remoteTombstoneDoesNotDeleteOriginalMedia() = runTest {
        val memoryId = "mem-media-safe"
        // Create a dummy file on disk to simulate original media
        val mediaFile = File(context.cacheDir, "original_screenshot_test.png")
        mediaFile.writeText("fake screenshot image bytes")
        assertTrue(mediaFile.exists())

        val localMemory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Screenshot with local media file",
            rawText = "Receipt",
            normalizedText = "receipt",
            sourceUri = mediaFile.toURI().toString(),
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        memoryRepository.saveMemory(localMemory, origin = MutationOrigin.LOCAL)
        cloudSyncOutboxDao.clearAll()

        // Remote tombstone
        val tombstoneRecord = CloudCryptoService.encryptTombstone(memoryId, revision = 2L, cloudSyncKey = cloudSyncKey)
        fakeCloudClient.upsertRecord(tombstoneRecord)

        cloudSyncManager.syncOnce()

        // Memory deleted
        assertNull(memoryDao.getMemoryById(memoryId))
        // Original media file still exists!
        assertTrue("Original media file must remain on disk", mediaFile.exists())

        mediaFile.delete()
    }

    @Test
    fun corruptedRemoteRecordLeavesLocalUntouched() = runTest {
        val memoryId = "mem-corrupted"
        val localMemory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Original Good Title",
            rawText = "Original good text",
            normalizedText = "original good text",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList()
        )
        memoryRepository.saveMemory(localMemory, origin = MutationOrigin.LOCAL)
        cloudSyncOutboxDao.clearAll()

        // Create remote record with corrupted ciphertext (fails GCM authentication)
        val validRecord = createRemoteMemoryRecord(memoryId, revision = 2L, title = "Corrupted Attempt", text = "Should not apply")
        val corruptedCipherBytes = Base64.getDecoder().decode(validRecord.ciphertextBase64)
        corruptedCipherBytes[0] = (corruptedCipherBytes[0].toInt() xor 0xFF).toByte()
        val corruptedRecord = validRecord.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(corruptedCipherBytes))

        fakeCloudClient.upsertRecord(corruptedRecord)

        // Perform sync pull
        val syncResult = cloudSyncManager.syncOnce()
        // Sync should record error or fail on corrupted record
        assertTrue("Sync should fail on corrupted record", syncResult is Result.Error)

        // Local memory remains completely untouched
        val currentLocal = memoryDao.getMemoryById(memoryId)
        assertEquals("Original Good Title", currentLocal?.title)
        assertEquals("Original good text", currentLocal?.rawText)
    }
}
