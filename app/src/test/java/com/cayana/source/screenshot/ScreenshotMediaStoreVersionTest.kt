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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotMediaStoreVersionTest {

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
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        ocrEngine = FakeOcrEngine(Result.Success(OcrResult(fullText = "Version Test")))
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
    fun `mediaStore version change resets baseline to avoid id wraparound`() = runTest {
        // Given an existing saved version that is obsolete (e.g. from before MediaProvider wipe / SD card remount)
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                screenshotWatcherStatus = ScreenshotWatcherStatus.ACTIVE,
                lastScreenshotMediaId = 999999L, // Obsolete high cursor from previous MediaStore instance
                mediaStoreVersion = "obsolete_version_123"
            )
        )
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = ocrEngine
        )
        val newSysVersion = "new_version_after_wipe"
        coordinator.mediaStoreVersionProvider = { newSysVersion }

        // Insert new items into current MediaStore (which has small IDs like 1, 2)
        val id1 = insertMockScreenshot("Screenshot_after_wipe_1.png")
        val id2 = insertMockScreenshot("Screenshot_after_wipe_2.png")

        // First run detects version mismatch ("obsolete_version_123" vs current)
        val firstRunMemories = coordinator.processPendingScreenshots()
        assertEquals("Version mismatch must trigger baseline re-establishment and skip existing items", 0, firstRunMemories.size)

        val updatedSettings = settingsRepository.getSettings().first()
        assertEquals("Saved version must update to current MediaStore version", newSysVersion, updatedSettings.mediaStoreVersion)
        assertEquals("Cursor must reset to current latest ID (id2)", id2, updatedSettings.lastScreenshotMediaId)

        // Subsequent item taken in this new MediaStore version is correctly ingested
        val id3 = insertMockScreenshot("Screenshot_after_wipe_3.png")
        val secondRunMemories = coordinator.processPendingScreenshots()
        assertEquals("Items created after baseline in new version must be ingested", 1, secondRunMemories.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())
        assertEquals(id3, settingsRepository.getSettings().first().lastScreenshotMediaId)
    }

    @Test
    fun `mediaStore getVersion exception does not reset baseline and preserves cursor`() = runTest {
        val initialCursor = 50L
        val savedVersion = "valid_version_1"
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                screenshotWatcherStatus = ScreenshotWatcherStatus.ACTIVE,
                lastScreenshotMediaId = initialCursor,
                mediaStoreVersion = savedVersion
            )
        )
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = ocrEngine
        )

        // Simulate MediaStore.getVersion throwing an exception / returning null
        coordinator.mediaStoreVersionProvider = { null }

        // An exception in getMediaStoreVersion should NOT reset baseline or cursor
        coordinator.processPendingScreenshots()
        val currentSettings = settingsRepository.getSettings().first()

        assertEquals("Cursor must remain unchanged when version check fails", initialCursor, currentSettings.lastScreenshotMediaId)
        assertEquals("Saved version must remain unchanged when version check fails", savedVersion, currentSettings.mediaStoreVersion)
    }
}
