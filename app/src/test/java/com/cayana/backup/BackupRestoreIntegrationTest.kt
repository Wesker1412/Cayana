package com.cayana.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.backup.drive.DriveAuthStatus
import com.cayana.backup.drive.FakeDriveAuthorizationManager
import com.cayana.backup.drive.FakeGoogleDriveBackupClient
import com.cayana.backup.manager.BackupManager
import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.core.common.Result
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceExistence
import com.cayana.source.SourceExistenceValidator
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var memoryRepository: RoomMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var driveClient: FakeGoogleDriveBackupClient
    private lateinit var driveAuthManager: FakeDriveAuthorizationManager
    private lateinit var recoveryKeyStorage: InMemoryRecoveryKeyStorage
    private lateinit var sourceExistenceValidator: SourceExistenceValidator
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        memoryRepository = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        settingsRepository = InMemorySettingsRepository()
        driveClient = FakeGoogleDriveBackupClient()
        driveAuthManager = FakeDriveAuthorizationManager(DriveAuthStatus.CONNECTED)
        recoveryKeyStorage = InMemoryRecoveryKeyStorage()

        sourceExistenceValidator = object : SourceExistenceValidator(context) {
            override fun doesSourceExist(uriString: String?): Boolean {
                // Simulate media missing on restored device
                return uriString != null && uriString.contains("valid_on_this_device")
            }
            override fun checkSourceExistence(uriString: String?, sourceType: SourceType?): SourceExistence {
                return if (doesSourceExist(uriString)) SourceExistence.Exists else SourceExistence.Missing
            }
        }

        backupManager = BackupManager(
            database = database,
            memoryDao = database.memoryDao(),
            calendarActionDao = database.calendarActionDao(),
            restoredCalendarActionHistoryDao = database.restoredCalendarActionHistoryDao(),
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            driveClient = driveClient,
            driveAuthManager = driveAuthManager,
            recoveryKeyStorage = recoveryKeyStorage,
            sourceExistenceValidator = sourceExistenceValidator
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun freshInstallRestoreRecoversMemoriesAndRebuildsSearch() = runBlocking {
        // 1. Populate Device 1 with memories
        val now = System.currentTimeMillis()
        val mem1 = MemoryItem(
            id = "mem-screenshot-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = now,
            capturedAt = now - 1000,
            title = "台北行高鐵車票",
            rawText = "台灣高鐵訂位代號 98765432 車次 0612 台北至左營 15:30",
            normalizedText = "台灣高鐵訂位代號 98765432 車次 0612 台北至左營 15:30",
            sourceUri = "content://media/non_existent_original.png",
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = listOf("高鐵", "台北"),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )
        val mem2 = MemoryItem(
            id = "mem-recording-2",
            sourceType = SourceType.RECORDING,
            createdAt = now,
            capturedAt = now - 500,
            title = "語音備忘錄",
            rawText = "下週一要進行雲端加密備份驗收測試",
            normalizedText = "下週一要進行雲端加密備份驗收測試",
            sourceUri = "content://media/non_existent_audio.wav",
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = listOf("雲端加密備份"),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )

        memoryRepository.saveMemory(mem1)
        memoryRepository.saveMemory(mem2)

        // Generate root key and configure
        val rootKey = RecoveryKeyManager.generateRootKey()
        recoveryKeyStorage.saveRecoveryKey(rootKey)
        val formattedKey = RecoveryKeyManager.formatKey(rootKey)

        // Execute Backup
        val backupResult = backupManager.performBackup()
        assertTrue("Backup should succeed", backupResult is Result.Success)
        val backupFileId = (backupResult as Result.Success).data.fileId

        // 2. Simulate Device 2: fresh install (clear local DB and local key)
        database.memoryDao().clearAll()
        database.searchDao().clearFts()
        recoveryKeyStorage.clearRecoveryKey()
        assertEquals(0, database.memoryDao().getAllMemoriesDirect().size)

        // 3. Execute Restore using Recovery Key
        val restoreResult = backupManager.restoreBackup(
            fileId = backupFileId,
            rawRecoveryKey = formattedKey
        )
        assertTrue("Restore should succeed", restoreResult is Result.Success)
        val summary = (restoreResult as Result.Success).data
        assertEquals(2, summary.memoriesRestored)

        // 4. Verify memories restored
        val restoredMemories = database.memoryDao().getAllMemoriesDirect()
        assertEquals(2, restoredMemories.size)

        val restoredOcr = restoredMemories.first { it.id == "mem-screenshot-1" }
        assertEquals("台北行高鐵車票", restoredOcr.title)
        assertTrue(restoredOcr.rawText?.contains("98765432") == true)
        // Missing original media marks sourceExists = false, but memory is preserved
        assertFalse("Missing media must mark sourceExists = false", restoredOcr.sourceExists)

        // 5. Verify search index is rebuilt and searchable
        val searchResultsOcr = memoryRepository.searchMemories("高鐵").first()
        assertEquals(1, searchResultsOcr.size)
        assertEquals("mem-screenshot-1", searchResultsOcr.first().id)

        val searchResultsVoice = memoryRepository.searchMemories("驗收測試").first()
        assertEquals(1, searchResultsVoice.size)
        assertEquals("mem-recording-2", searchResultsVoice.first().id)
    }

    @Test
    fun wrongKeyLeavesLocalDatabaseUntouched() = runBlocking {
        // Setup existing local memory on device
        val existing = MemoryItem(
            id = "local-existing-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000,
            capturedAt = 1000,
            title = "Existing Local Item",
            rawText = "Do not delete me",
            normalizedText = "Do not delete me",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )
        memoryRepository.saveMemory(existing)

        // Create backup with Key A
        val keyA = RecoveryKeyManager.generateRootKey()
        recoveryKeyStorage.saveRecoveryKey(keyA)
        val backupResult = backupManager.performBackup()
        val fileId = (backupResult as Result.Success).data.fileId

        // Attempt restore with Wrong Key B
        val wrongKeyB = RecoveryKeyManager.generateRootKey()
        val formattedWrongKey = RecoveryKeyManager.formatKey(wrongKeyB)

        val restoreResult = backupManager.restoreBackup(
            fileId = fileId,
            rawRecoveryKey = formattedWrongKey
        )
        assertTrue("Restore must fail with wrong key", restoreResult is Result.Error)

        // Verify local DB is completely untouched
        val currentLocal = database.memoryDao().getAllMemoriesDirect()
        assertEquals(1, currentLocal.size)
        assertEquals("local-existing-1", currentLocal.first().id)
        assertEquals("Do not delete me", currentLocal.first().rawText)
    }

    @Test
    fun corruptedBackupLeavesLocalDatabaseUntouched() = runBlocking {
        val existing = MemoryItem(
            id = "local-existing-2",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000,
            capturedAt = 1000,
            title = "Existing Local Item 2",
            rawText = "Safe content",
            normalizedText = "Safe content",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )
        memoryRepository.saveMemory(existing)

        val key = RecoveryKeyManager.generateRootKey()
        recoveryKeyStorage.saveRecoveryKey(key)
        val formattedKey = RecoveryKeyManager.formatKey(key)
        val backupResult = backupManager.performBackup()
        val fileId = (backupResult as Result.Success).data.fileId

        // Tamper with single byte in remote drive storage
        val originalBlob = driveClient.getStoredContent(fileId)!!
        val tamperedBlob = originalBlob.copyOf()
        tamperedBlob[tamperedBlob.size - 8] = (tamperedBlob[tamperedBlob.size - 8].toInt() xor 0x01).toByte()
        // overwrite stored blob in fake drive
        val fakeStoreField = FakeGoogleDriveBackupClient::class.java.getDeclaredField("storage")
        fakeStoreField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val storageMap = fakeStoreField.get(driveClient) as MutableMap<String, Pair<*, ByteArray>>
        val meta = storageMap[fileId]!!.first
        storageMap[fileId] = Pair(meta, tamperedBlob)

        // Attempt restore
        val restoreResult = backupManager.restoreBackup(
            fileId = fileId,
            rawRecoveryKey = formattedKey
        )
        assertTrue("Restore must fail on corrupted ciphertext", restoreResult is Result.Error)

        // Verify local DB is untouched
        val currentLocal = database.memoryDao().getAllMemoriesDirect()
        assertEquals(1, currentLocal.size)
        assertEquals("local-existing-2", currentLocal.first().id)
    }

    @Test
    fun restoreDoesNotReplayCalendarEventsAndRestoredActionsAreHistoricalOnly() = runBlocking {
        val now = System.currentTimeMillis()
        val mem = MemoryItem(
            id = "mem-cal-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = now,
            capturedAt = now,
            title = "Calendar Screenshot",
            rawText = "Meet with doctor tomorrow",
            normalizedText = "Meet with doctor tomorrow",
            sourceUri = null,
            sourceUrl = null,
            sourceExists = true,
            metadata = emptyMap(),
            entities = emptyList(),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )
        memoryRepository.saveMemory(mem)

        val liveAction = CalendarActionEntity(
            id = "live-action-1",
            memoryId = "mem-cal-1",
            calendarId = 10L,
            calendarEventId = 777L,
            actionType = "CREATE_EVENT",
            createdAt = now,
            status = "CONFIRMED",
            title = "看診預約",
            startAt = now + 100000,
            endAt = now + 200000,
            location = "診所",
            isAllDay = false,
            zoneId = "Asia/Taipei"
        )
        database.calendarActionDao().insert(liveAction)

        val rootKey = RecoveryKeyManager.generateRootKey()
        recoveryKeyStorage.saveRecoveryKey(rootKey)
        val formattedKey = RecoveryKeyManager.formatKey(rootKey)

        val backupResult = backupManager.performBackup()
        val fileId = (backupResult as Result.Success).data.fileId

        // Clear local database to simulate fresh restore
        database.memoryDao().clearAll()
        database.calendarActionDao().deleteByMemoryId("mem-cal-1")
        database.restoredCalendarActionHistoryDao().clearAll()

        assertEquals(0, database.calendarActionDao().getAll().size)

        // Execute Restore
        val restoreResult = backupManager.restoreBackup(
            fileId = fileId,
            rawRecoveryKey = formattedKey
        )
        assertTrue(restoreResult is Result.Success)

        // CRITICAL INVARIANT VERIFICATIONS:
        // 1. Live calendar_actions table must remain EMPTY (0 rows)
        val liveActionsAfterRestore = database.calendarActionDao().getAll()
        assertEquals("Live calendar_actions table must NEVER be populated on restore", 0, liveActionsAfterRestore.size)

        // 2. Restored historical actions table contains the inert record
        val historicalActions = database.restoredCalendarActionHistoryDao().getAll()
        assertEquals(1, historicalActions.size)
        val hist = historicalActions.first()
        assertEquals("live-action-1", hist.originalActionId)
        assertEquals("mem-cal-1", hist.memoryId)
        assertEquals("看診預約", hist.title)
        assertEquals(777L, hist.originalCalendarEventId)
    }
}
