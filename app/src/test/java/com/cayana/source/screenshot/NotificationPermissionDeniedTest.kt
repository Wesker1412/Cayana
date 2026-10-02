package com.cayana.source.screenshot

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.core.notification.NotificationHelper
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrResult
import com.cayana.source.SourceType
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
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
class NotificationPermissionDeniedTest {

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
        ocrEngine = FakeOcrEngine(Result.Success(OcrResult(fullText = "Notification Test Raw Text")))
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = ocrEngine
        )
    }

    @Test
    fun `NotificationHelper directly handles missing permission without throwing exception`() {
        val memoryItem = MemoryItem(
            sourceType = SourceType.SCREENSHOT,
            title = "Test Title",
            rawText = "Notification Body Content",
            sourceUri = "content://media/external/images/media/300"
        )
        // Calling showMemoryIngestedNotification should silently complete without throwing
        NotificationHelper.showMemoryIngestedNotification(context, memoryItem)
    }

    @Test
    fun `memory is persisted even when notification permission is denied`() = runTest {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_without_notif.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "Screenshots")
            put(MediaStore.Images.Media.SIZE, 1024L)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        assertNotNull(uri)

        val processed = coordinator.processPendingScreenshots()
        assertEquals(1, processed.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())
        assertEquals("Test Title or derived line", "Notification Test Raw Text", processed.first().rawText)
    }
}
