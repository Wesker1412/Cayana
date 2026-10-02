package com.cayana.source.screenshot

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrResult
import com.cayana.test.FakeMediaContentProvider
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotCatchUpTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var ocrEngine: FakeOcrEngine
    private lateinit var coordinator: ScreenshotProcessingCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaContentProvider.register(context)
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository()
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        ocrEngine = FakeOcrEngine(Result.Success(OcrResult(fullText = "Catch Up Event Text")))
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = ocrEngine
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
    fun `batch ingestion processes all pending screenshots and advances cursor`() = runTest {
        // Insert 3 screenshots
        val id1 = insertMockScreenshot("Screenshot_1.png")
        val id2 = insertMockScreenshot("Screenshot_2.png")
        val id3 = insertMockScreenshot("Screenshot_3.png")

        val processed = coordinator.processPendingScreenshots()
        assertEquals("Should ingest all 3 pending screenshots", 3, processed.size)
        assertEquals(3, memoryRepository.getMemoryCount().first())

        val lastId = settingsRepository.getSettings().first().lastScreenshotMediaId
        assertEquals("Cursor must advance to the highest media id", id3, lastId)

        // Running again should find 0 new items
        val secondPass = coordinator.processPendingScreenshots()
        assertEquals("Second pass without new items should return 0 items", 0, secondPass.size)
    }

    @Test
    fun `initializeCursorToLatest sets cursor to latest item and avoids historical processing`() = runTest {
        val historicalId = insertMockScreenshot("Screenshot_old.png")
        
        // Settings start with lastScreenshotMediaId = 0
        assertEquals(0L, settingsRepository.getSettings().first().lastScreenshotMediaId)

        // Initialize cursor
        val initializedId = coordinator.initializeCursorToLatest()
        assertEquals(historicalId, initializedId)
        assertEquals(historicalId, settingsRepository.getSettings().first().lastScreenshotMediaId)

        // Process pending - should ingest nothing because cursor is already at historicalId
        val processed = coordinator.processPendingScreenshots()
        assertEquals(0, processed.size)
        assertEquals(0, memoryRepository.getMemoryCount().first())

        // Now a new screenshot arrives
        val newId = insertMockScreenshot("Screenshot_new.png")
        val newProcessed = coordinator.processPendingScreenshots()
        assertEquals(1, newProcessed.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())
        assertEquals(newId, settingsRepository.getSettings().first().lastScreenshotMediaId)
    }
}
