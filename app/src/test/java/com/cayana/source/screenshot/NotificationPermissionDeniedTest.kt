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
import android.app.Application
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.logging.DefaultCayanaLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        settingsRepository = InMemorySettingsRepository(
            initialSettings = com.cayana.ui.settings.repository.UserSettings(
                screenshotWatcherStatus = com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE
            )
        )
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

    @Test
    fun `notification logging never includes OCR memory text`() {
        val shadowApp = shadowOf(context.applicationContext as Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val recordingLogger = RecordingLogger()
        CayanaLogger.setDelegate(recordingLogger)

        try {
            val secretText = "SECRET_PRIVATE_OCR_TEXT_123"
            val item = MemoryItem(
                sourceType = SourceType.SCREENSHOT,
                title = "Secret Screenshot",
                rawText = secretText,
                sourceUri = "content://media/external/images/media/777"
            )

            NotificationHelper.showMemoryIngestedNotification(context, item)

            // Confirm notification operational log occurred
            val hasNotificationLog = recordingLogger.messages.any { it.contains("Posted memory notification id=${item.id}") }
            assertTrue("Expected operational notification log for memory", hasNotificationLog)

            // Confirm raw OCR text is never present in any log message
            val hasSecretText = recordingLogger.messages.any { it.contains(secretText) }
            assertFalse("CayanaLogger leaked raw OCR text in log messages!", hasSecretText)

            // Confirm notification preview snippet is never present in any log message
            val hasPreviewSnippet = recordingLogger.messages.any { it.contains("SECRET_PRIVATE") }
            assertFalse("CayanaLogger leaked notification preview in log messages!", hasPreviewSnippet)
        } finally {
            CayanaLogger.setDelegate(DefaultCayanaLogger())
        }
    }

    private class RecordingLogger : CayanaLogger {
        val messages = mutableListOf<String>()
        override fun d(tag: String, message: String) { messages.add(message) }
        override fun i(tag: String, message: String) { messages.add(message) }
        override fun w(tag: String, message: String, throwable: Throwable?) { messages.add(message) }
        override fun e(tag: String, message: String, throwable: Throwable?) { messages.add(message) }
        override fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?) {
            messages.add("$eventName: $memoryId")
        }
    }
}
