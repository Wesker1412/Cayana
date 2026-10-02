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
    fun `batch ingestion processes all pending screenshots and advances cursor when active`() = runTest {
        // Given watcher is in ACTIVE tracking mode
        settingsRepository.updateScreenshotWatcherStatus(com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE)

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
    fun `baseline on first authorization skips historical screenshots`() = runTest {
        // Initial state is UNINITIALIZED with 0 cursor
        val settings = settingsRepository.getSettings().first()
        assertEquals(com.cayana.ui.settings.repository.ScreenshotWatcherStatus.UNINITIALIZED, settings.screenshotWatcherStatus)
        assertEquals(0L, settings.lastScreenshotMediaId)

        // MediaStore already contains historical screenshots
        val hist1 = insertMockScreenshot("Hist_1.png")
        val hist2 = insertMockScreenshot("Hist_2.png")

        // First full authorization triggers coordinator
        val firstRunMemories = coordinator.processPendingScreenshots()
        assertEquals("Historical screenshots must NOT be ingested on first authorization", 0, firstRunMemories.size)
        assertEquals(0, memoryRepository.getMemoryCount().first())

        // Verify baseline cursor was established at highest historical ID and status is ACTIVE
        val updatedSettings = settingsRepository.getSettings().first()
        assertEquals("Cursor must be baseline at latest historical ID", hist2, updatedSettings.lastScreenshotMediaId)
        assertEquals("Status must transition to ACTIVE", com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE, updatedSettings.screenshotWatcherStatus)

        // Only screenshots taken AFTER authorization baseline are ingested
        val newScreenshotId = insertMockScreenshot("Screenshot_after_auth.png")
        val secondRunMemories = coordinator.processPendingScreenshots()
        assertEquals("New screenshot taken after authorization should be ingested", 1, secondRunMemories.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())
        assertEquals(newScreenshotId, settingsRepository.getSettings().first().lastScreenshotMediaId)
    }

    @Test
    fun `baseline on re-enable skips screenshots taken during disabled period`() = runTest {
        // User actively tracks screenshots
        settingsRepository.updateScreenshotWatcherStatus(com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE)
        val initialId = insertMockScreenshot("Screenshot_active_1.png")
        val activeMemories = coordinator.processPendingScreenshots()
        assertEquals(1, activeMemories.size)
        assertEquals(initialId, settingsRepository.getSettings().first().lastScreenshotMediaId)

        // User actively disables Screenshots
        settingsRepository.updateSourceEnabled(com.cayana.source.SourceType.SCREENSHOT, false)
        settingsRepository.updateScreenshotWatcherStatus(com.cayana.ui.settings.repository.ScreenshotWatcherStatus.DISABLED)

        // Screenshots taken while disabled
        val disabledId1 = insertMockScreenshot("Screenshot_disabled_1.png")
        val disabledId2 = insertMockScreenshot("Screenshot_disabled_2.png")

        // User re-enables Screenshots
        settingsRepository.updateSourceEnabled(com.cayana.source.SourceType.SCREENSHOT, true)

        // Re-enabling coordinator triggers new baseline
        val reEnableMemories = coordinator.processPendingScreenshots()
        assertEquals("Screenshots during disabled period must NOT be ingested", 0, reEnableMemories.size)
        assertEquals("Memory count must remain 1 from initial run", 1, memoryRepository.getMemoryCount().first())

        val postReEnableSettings = settingsRepository.getSettings().first()
        assertEquals("Baseline cursor must advance to latest disabled screenshot", disabledId2, postReEnableSettings.lastScreenshotMediaId)
        assertEquals("Status must transition back to ACTIVE", com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE, postReEnableSettings.screenshotWatcherStatus)

        // Subsequent screenshot taken after re-enabling IS ingested
        val newId = insertMockScreenshot("Screenshot_after_re-enable.png")
        val postMemories = coordinator.processPendingScreenshots()
        assertEquals("Post-reenable screenshot must be ingested", 1, postMemories.size)
        assertEquals(2, memoryRepository.getMemoryCount().first())
        assertEquals(newId, settingsRepository.getSettings().first().lastScreenshotMediaId)
    }

    @Test
    fun `process restart or reboot preserves active cursor and catches up pending screenshots`() = runTest {
        // App was actively tracking at cursor = initialId
        val initialId = insertMockScreenshot("Screenshot_before_kill.png")
        settingsRepository.updateScreenshotWatcherStatus(com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE)
        val initialRun = coordinator.processPendingScreenshots()
        assertEquals(1, initialRun.size)
        assertEquals(initialId, settingsRepository.getSettings().first().lastScreenshotMediaId)

        // App process dies (simulated: status remains ACTIVE, cursor remains initialId)
        // Two screenshots occur while app process is not running
        val offlineId1 = insertMockScreenshot("Screenshot_offline_1.png")
        val offlineId2 = insertMockScreenshot("Screenshot_offline_2.png")

        // App process restarts, coordinator runs catch-up
        val catchUpRun = coordinator.processPendingScreenshots()
        assertEquals("Must catch up all screenshots taken during process death", 2, catchUpRun.size)
        assertEquals(3, memoryRepository.getMemoryCount().first())
        assertEquals("Cursor must advance to the latest caught up screenshot", offlineId2, settingsRepository.getSettings().first().lastScreenshotMediaId)
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
