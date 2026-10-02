package com.cayana.source.screenshot

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrResult
import com.cayana.processing.ProcessingState
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
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
class MemoryPersistenceOcrFailureTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var ocrEngine: FakeOcrEngine
    private lateinit var coordinator: ScreenshotProcessingCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        com.cayana.test.FakeMediaContentProvider.register(context)
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository()
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        ocrEngine = FakeOcrEngine()
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = ocrEngine
        )
    }

    private fun insertMockScreenshot(name: String): String {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "Screenshots")
            put(MediaStore.Images.Media.SIZE, 1024L)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        return uri.toString()
    }

    @Test
    fun `screenshot persists even when OCR engine fails with exception`() = runTest {
        ocrEngine.simulatedResult = Result.Error(IOException("Corrupted image or OCR failure"))
        val uri = insertMockScreenshot("Screenshot_no_text.png")

        val processed = coordinator.processPendingScreenshots()
        assertEquals(1, processed.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())

        val saved = memoryRepository.getMemoryBySourceUri(uri)
        assertNotNull(saved)
        assertEquals(ProcessingState.COMPLETED_WITHOUT_TEXT, saved?.processingState)
        assertNull("Raw text should be null on OCR failure", saved?.rawText)
        assertEquals("Screenshot_no_text", saved?.title)
    }

    @Test
    fun `screenshot persists with COMPLETED_WITHOUT_TEXT when image contains no text`() = runTest {
        ocrEngine.simulatedResult = Result.Success(OcrResult(fullText = "   \n\t  "))
        val uri = insertMockScreenshot("Screenshot_blank.png")

        val processed = coordinator.processPendingScreenshots()
        assertEquals(1, processed.size)

        val saved = memoryRepository.getMemoryBySourceUri(uri)
        assertNotNull(saved)
        assertEquals(ProcessingState.COMPLETED_WITHOUT_TEXT, saved?.processingState)
        assertNull(saved?.rawText)
        assertEquals("Screenshot_blank", saved?.title)
    }
}
