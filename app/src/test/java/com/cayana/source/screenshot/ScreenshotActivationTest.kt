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
import com.cayana.ui.settings.repository.ScreenshotWatcherStatus
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotActivationTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var testOcrEngine: FakeOcrEngine
    private lateinit var coordinator: ScreenshotProcessingCoordinator
    private lateinit var watcher: ScreenshotSourceWatcher

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaContentProvider.register(context)
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                screenshotWatcherStatus = ScreenshotWatcherStatus.UNINITIALIZED,
                lastScreenshotMediaId = 0L
            )
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        testOcrEngine = FakeOcrEngine(Result.Success(OcrResult("Activation Test OCR")))
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = testOcrEngine
        )
        watcher = ScreenshotSourceWatcher(
            context = context,
            permissionChecker = permissionChecker,
            coordinator = coordinator,
            settingsRepository = settingsRepository
        )
    }

    private fun insertMockScreenshot(name: String): Long {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "Screenshots")
            put(MediaStore.Images.Media.SIZE, 1024L)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        return ContentUris.parseId(uri!!)
    }

    @Test
    fun `activation establishes baseline first and only subsequent screenshots are ingested`() = runTest {
        // Given historical screenshots exist in MediaStore prior to activation
        val hist1 = insertMockScreenshot("Hist1.png")
        val hist2 = insertMockScreenshot("Hist2.png")
        val hist3 = insertMockScreenshot("Hist3.png")

        // Initial state before activation
        val initialSettings = settingsRepository.getSettings().first()
        assertEquals(ScreenshotWatcherStatus.UNINITIALIZED, initialSettings.screenshotWatcherStatus)
        assertEquals(0L, initialSettings.lastScreenshotMediaId)

        // When activating watcher
        watcher.activateWatching()

        // Then baseline must be established BEFORE any ingestion:
        val postActivationSettings = settingsRepository.getSettings().first()
        assertEquals(ScreenshotWatcherStatus.ACTIVE, postActivationSettings.screenshotWatcherStatus)
        assertEquals("Baseline must be set to the latest historical screenshot ID", hist3, postActivationSettings.lastScreenshotMediaId)
        assertEquals("Historical screenshots must NOT be ingested into Room", 0, memoryRepository.getMemoryCount().first())

        // Now a new screenshot is captured after activation
        val newId = insertMockScreenshot("NewScreenshot.png")
        val newMemories = coordinator.processPendingScreenshots()

        assertEquals("Only new screenshot captured after baseline must be ingested", 1, newMemories.size)
        val memory = memoryRepository.getAllMemories().first().first()
        assertEquals("mediaStoreId", newId.toString(), memory.metadata["mediaStoreId"])
        assertEquals("NewScreenshot.png", memory.metadata["displayName"])
        assertEquals(newId, settingsRepository.getSettings().first().lastScreenshotMediaId)

        watcher.stopWatching()
    }
}
