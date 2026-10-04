package com.cayana.source.screenshot

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrEngine
import com.cayana.processing.OcrResult
import com.cayana.processing.ProcessingState
import com.cayana.test.FakeMediaContentProvider
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.ScreenshotWatcherStatus
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotOcrReliabilityTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var testOcrEngine: ConfigurableTestOcrEngine
    private lateinit var coordinator: ScreenshotProcessingCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaContentProvider.register(context)
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(screenshotWatcherStatus = ScreenshotWatcherStatus.ACTIVE)
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        testOcrEngine = ConfigurableTestOcrEngine()
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = testOcrEngine
        )
    }

    private fun insertMockScreenshot(name: String): Long {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "Screenshots")
            put(MediaStore.Images.Media.SIZE, 2048L)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        return ContentUris.parseId(uri!!)
    }

    @Test
    fun `empty text produces COMPLETED_WITHOUT_TEXT while preserving memory item`() = runTest {
        val id = insertMockScreenshot("BlankScreenshot.png")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
        testOcrEngine.setResultForUri(uri, Result.Success(OcrResult(fullText = "")))

        val processed = coordinator.processPendingScreenshots()
        assertEquals(1, processed.size)

        val memory = memoryRepository.getMemoryBySourceUri(uri)
        assertNotNull("MemoryItem must be saved in Room", memory)
        assertEquals("Empty text must result in COMPLETED_WITHOUT_TEXT", ProcessingState.COMPLETED_WITHOUT_TEXT, memory?.processingState)
        assertNull("Raw text must be null", memory?.rawText)
        assertEquals("Title should derive from filename when text is empty", "BlankScreenshot", memory?.title)
    }

    @Test
    fun `ocr error produces FAILED_RETRYABLE and retryPendingOcr recovers item`() = runTest {
        val id = insertMockScreenshot("CorruptScreenshot.png")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
        // Simulate temporary OCR failure / IOException
        testOcrEngine.setResultForUri(uri, Result.Error(IOException("ML Kit OCR OutOfMemory / Transient Error")))

        val processed = coordinator.processPendingScreenshots()
        assertEquals("Item must still be created even when OCR fails", 1, processed.size)

        val memoryBeforeRetry = memoryRepository.getMemoryBySourceUri(uri)
        assertNotNull(memoryBeforeRetry)
        assertEquals("OCR error must result in FAILED_RETRYABLE", ProcessingState.FAILED_RETRYABLE, memoryBeforeRetry?.processingState)

        // Simulate subsequent recovery (e.g. background maintenance or user-triggered retry)
        testOcrEngine.setResultForUri(uri, Result.Success(OcrResult(fullText = "Recovered Receipt Text 2026/10/02")))

        val retriedCount = coordinator.retryPendingOcr()
        assertEquals("Must successfully retry 1 pending item", 1, retriedCount)

        val memoryAfterRetry = memoryRepository.getMemoryBySourceUri(uri)
        assertNotNull(memoryAfterRetry)
        assertEquals("State must now be COMPLETED", ProcessingState.COMPLETED, memoryAfterRetry?.processingState)
        assertEquals("Recovered Receipt Text 2026/10/02", memoryAfterRetry?.rawText)
    }

    @Test
    fun `pipeline automatically retries FAILED_RETRYABLE during next processing cycle`() = runTest {
        val id1 = insertMockScreenshot("FailFirst.png")
        val uri1 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id1).toString()
        testOcrEngine.setResultForUri(uri1, Result.Error(IOException("Transient OCR failure")))

        // First pipeline cycle: fails and marked FAILED_RETRYABLE
        val firstCycle = coordinator.processPendingScreenshots()
        assertEquals(1, firstCycle.size)
        assertEquals(ProcessingState.FAILED_RETRYABLE, memoryRepository.getMemoryBySourceUri(uri1)?.processingState)

        // OCR engine recovers, and a new screenshot arrives
        testOcrEngine.setResultForUri(uri1, Result.Success(OcrResult(fullText = "Recovered Content On Next Cycle")))
        val id2 = insertMockScreenshot("SecondScreenshot.png")
        val uri2 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id2).toString()
        testOcrEngine.setResultForUri(uri2, Result.Success(OcrResult(fullText = "Second Screenshot Text")))

        // Second pipeline cycle: processes id2 AND automatically retries id1
        val secondCycle = coordinator.processPendingScreenshots()
        assertEquals(1, secondCycle.size)

        val memory1 = memoryRepository.getMemoryBySourceUri(uri1)
        assertNotNull(memory1)
        assertEquals("Previous retryable item must now be COMPLETED", ProcessingState.COMPLETED, memory1?.processingState)
        assertEquals("Recovered Content On Next Cycle", memory1?.rawText)
    }

    @Test
    fun `deleted screenshot file during retry is marked FAILED_PERMANENT with sourceExists false`() = runTest {
        val id = insertMockScreenshot("DeletedLater.png")
        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val uriString = contentUri.toString()
        testOcrEngine.setResultForUri(uriString, Result.Error(IOException("Temporary failure")))

        coordinator.processPendingScreenshots()
        val initialMemory = memoryRepository.getMemoryBySourceUri(uriString)
        assertEquals(ProcessingState.FAILED_RETRYABLE, initialMemory?.processingState)

        // File is deleted from device/MediaStore
        context.contentResolver.delete(contentUri, null, null)

        // Run retry
        coordinator.retryPendingOcr()

        val updatedMemory = memoryRepository.getMemoryBySourceUri(uriString)
        assertNotNull(updatedMemory)
        assertEquals(ProcessingState.FAILED_PERMANENT, updatedMemory?.processingState)
        assertEquals(false, updatedMemory?.sourceExists)
    }

    @Test
    fun `max retry limit transitions item to FAILED_PERMANENT`() = runTest {
        val id = insertMockScreenshot("PersistentFlake.png")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
        testOcrEngine.setResultForUri(uri, Result.Error(IOException("Permanent flake")))

        coordinator.processPendingScreenshots()

        // Retry 5 times
        repeat(5) {
            coordinator.retryPendingOcr()
        }

        val finalMemory = memoryRepository.getMemoryBySourceUri(uri)
        assertNotNull(finalMemory)
        assertEquals(ProcessingState.FAILED_PERMANENT, finalMemory?.processingState)
        assertEquals("5", finalMemory?.metadata?.get("ocrRetryCount"))
    }

    private class ConfigurableTestOcrEngine : OcrEngine {
        override val isReady: Boolean = true
        private val uriResults = mutableMapOf<String, Result<OcrResult>>()

        fun setResultForUri(uri: String, result: Result<OcrResult>) {
            uriResults[uri] = result
        }

        override suspend fun extractText(uri: String): Result<String> {
            return when (val res = processImage(uri)) {
                is Result.Success -> Result.Success(res.data.fullText)
                is Result.Error -> Result.Error(res.exception)
                Result.Loading -> Result.Loading
            }
        }

        override suspend fun processImage(uri: String): Result<OcrResult> {
            return uriResults[uri] ?: Result.Success(OcrResult(fullText = "Default Text"))
        }
    }
}
