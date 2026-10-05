package com.cayana.source.photo

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrResult
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoPipelineIntegrationTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var ocrEngine: FakeOcrEngine
    private lateinit var coordinator: PhotoProcessingCoordinator
    private lateinit var watcher: PhotoSourceWatcher

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        com.cayana.test.FakeMediaContentProvider.register(context)
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                enabledSources = setOf(SourceType.PHOTO),
                photoWatcherStatus = SourceWatcherStatus.ACTIVE
            )
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        ocrEngine = FakeOcrEngine(Result.Success(OcrResult(fullText = "Taipei Concert Ticket 10/18")))
        coordinator = PhotoProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = ocrEngine
        )
        watcher = PhotoSourceWatcher(
            context = context,
            permissionChecker = permissionChecker,
            coordinator = coordinator,
            settingsRepository = settingsRepository
        )
    }

    private fun insertFakePhoto(
        name: String = "IMG_20261005_120000.jpg",
        path: String = "DCIM/Camera/",
        bucket: String = "Camera",
        mime: String = "image/jpeg"
    ): Long {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, bucket)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.SIZE, 2048L)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.WIDTH, 1920)
            put(MediaStore.Images.Media.HEIGHT, 1080)
            put(MediaStore.Images.Media.ORIENTATION, 0)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        assertNotNull(uri)
        return android.content.ContentUris.parseId(uri!!)
    }

    @Test
    fun newNormalPhotoCreatesOnePhotoMemory() = runTest {
        insertFakePhoto("IMG_001.jpg", "DCIM/Camera/", "Camera")
        val processed = coordinator.processPendingPhotos()

        assertEquals(1, processed.size)
        val item = processed[0]
        assertEquals(SourceType.PHOTO, item.sourceType)
        assertEquals("Taipei Concert Ticket 10/18", item.rawText)
        assertEquals(ProcessingState.COMPLETED, item.processingState)
        assertTrue(item.sourceExists)
    }

    @Test
    fun screenshotImageDoesNotCreatePhotoMemory() = runTest {
        insertFakePhoto(
            name = "Screenshot_20261005-120000.png",
            path = "Pictures/Screenshots/",
            bucket = "Screenshots",
            mime = "image/png"
        )
        val processed = coordinator.processPendingPhotos()
        assertEquals(0, processed.size)
        assertEquals(0, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun samePhotoTriggeredTwiceCreatesOnlyOneMemory() = runTest {
        insertFakePhoto("IMG_dedup.jpg")

        val firstRun = coordinator.processPendingPhotos()
        assertEquals(1, firstRun.size)

        val secondRun = coordinator.processPendingPhotos()
        assertEquals(0, secondRun.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun firstPhotoAuthorizationEstablishesBaselineAndDoesNotImportHistoricalPhotos() = runTest {
        // Reset settings to UNINITIALIZED
        settingsRepository.updatePhotoWatcherStatus(SourceWatcherStatus.UNINITIALIZED)
        settingsRepository.updateLastPhotoMediaId(0L)

        // Insert historical photo before activation
        val historicalId = insertFakePhoto("IMG_historical.jpg")

        // First process cycle should establish baseline
        val firstRun = coordinator.processPendingPhotos()
        assertEquals("Historical photos must not be ingested on first activation", 0, firstRun.size)
        assertEquals(0, memoryRepository.getMemoryCount().first())

        val settingsAfterBaseline = settingsRepository.getSettings().first()
        assertEquals(SourceWatcherStatus.ACTIVE, settingsAfterBaseline.photoWatcherStatus)
        assertTrue(settingsAfterBaseline.lastPhotoMediaId >= historicalId)

        // New photo created after baseline established
        insertFakePhoto("IMG_after_baseline.jpg")
        val secondRun = coordinator.processPendingPhotos()
        assertEquals("Only photo created after baseline should be ingested", 1, secondRun.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun processRestartCatchesUpMissedPhotos() = runTest {
        val id1 = insertFakePhoto("IMG_old.jpg")
        settingsRepository.updateLastPhotoMediaId(id1)
        settingsRepository.updatePhotoWatcherStatus(SourceWatcherStatus.ACTIVE)

        // Simulate app was killed while new photos arrived
        val id2 = insertFakePhoto("IMG_missed_1.jpg")
        val id3 = insertFakePhoto("IMG_missed_2.jpg")

        // Process restart catch-up
        val processed = coordinator.processPendingPhotos()
        assertEquals(2, processed.size)
        assertEquals(2, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun photoOcrSuccessYieldsSearchableText() = runTest {
        ocrEngine.simulatedResult = Result.Success(OcrResult(fullText = "Searchable Receipt 500 NTD"))
        insertFakePhoto("IMG_receipt.jpg")

        val processed = coordinator.processPendingPhotos()
        assertEquals(1, processed.size)
        assertEquals("Searchable Receipt 500 NTD", processed[0].rawText)
        assertEquals(ProcessingState.COMPLETED, processed[0].processingState)
    }

    @Test
    fun photoNoTextPersistsCompletedWithoutText() = runTest {
        ocrEngine.simulatedResult = Result.Success(OcrResult(fullText = "   "))
        insertFakePhoto("IMG_blank.jpg")

        val processed = coordinator.processPendingPhotos()
        assertEquals(1, processed.size)
        assertNull(processed[0].rawText)
        assertEquals(ProcessingState.COMPLETED_WITHOUT_TEXT, processed[0].processingState)
        assertEquals(1, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun photoOcrFailurePersistsFailedRetryable() = runTest {
        ocrEngine.simulatedResult = Result.Error(RuntimeException("OCR engine crash"))
        insertFakePhoto("IMG_corrupt.jpg")

        val processed = coordinator.processPendingPhotos()
        assertEquals(1, processed.size)
        assertNull(processed[0].rawText)
        assertEquals(ProcessingState.FAILED_RETRYABLE, processed[0].processingState)
        assertEquals(1, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun originalPhotoDeletedMarksSourceExistsFalse() = runTest {
        insertFakePhoto("IMG_to_delete.jpg")
        val processed = coordinator.processPendingPhotos()
        assertEquals(1, processed.size)
        val memory = processed[0]
        assertTrue(memory.sourceExists)

        // Delete from MediaStore
        val uri = android.net.Uri.parse(memory.sourceUri!!)
        context.contentResolver.delete(uri, null, null)

        val updatedCount = coordinator.reconcileDeletedPhotos()
        assertEquals(1, updatedCount)

        val updatedMemory = memoryRepository.getMemoryById(memory.id).first()
        assertNotNull(updatedMemory)
        assertFalse("SourceExists must be false after deletion", updatedMemory!!.sourceExists)
        assertEquals("OCR text must be preserved after source deletion", memory.rawText, updatedMemory.rawText)
    }

    @Test
    fun limitedAccessDoesNotAutoIngest() = runTest {
        permissionChecker.apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", false)
            setPermissionGranted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED", true)
        }
        insertFakePhoto("IMG_limited.jpg")

        val processed = coordinator.processPendingPhotos()
        assertEquals("LIMITED_ACCESS must not trigger automatic background watcher ingestion", 0, processed.size)
        assertEquals(0, memoryRepository.getMemoryCount().first())
    }
}
