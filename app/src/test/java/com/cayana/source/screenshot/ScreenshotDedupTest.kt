package com.cayana.source.screenshot

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotDedupTest {

    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var ocrEngine: FakeOcrEngine
    private lateinit var coordinator: ScreenshotProcessingCoordinator

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository(
            initialSettings = com.cayana.ui.settings.repository.UserSettings(
                screenshotWatcherStatus = com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE
            )
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        ocrEngine = FakeOcrEngine(Result.Success(OcrResult(fullText = "Dedup Test Content")))
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = ocrEngine
        )
    }

    @Test
    fun `deterministic UUID from sourceUri generates identical IDs`() {
        val uri = "content://media/external/images/media/105"
        val id1 = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
        val id2 = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
        val idDifferent = UUID.nameUUIDFromBytes("content://media/external/images/media/106".toByteArray()).toString()

        assertEquals("Same URI must produce identical deterministic UUID", id1, id2)
        org.junit.Assert.assertNotEquals("Different URIs must produce different UUIDs", id1, idDifferent)
    }

    @Test
    fun `getMemoryBySourceUri returns existing item and prevents duplicate ingestion`() = runTest {
        val uri = "content://media/external/images/media/200"
        val existingItem = MemoryItem(
            id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString(),
            sourceType = SourceType.SCREENSHOT,
            title = "Existing Screenshot",
            rawText = "Existing text",
            sourceUri = uri
        )
        memoryRepository.saveMemory(existingItem)

        val found = memoryRepository.getMemoryBySourceUri(uri)
        assertNotNull("Should find existing memory item by sourceUri", found)
        assertEquals(existingItem.id, found?.id)
        assertEquals(1, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun `coordinator skips already ingested screenshot`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_20261002.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "Screenshots")
            put(MediaStore.Images.Media.SIZE, 1024L)
        }
        val insertedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        assertNotNull("MediaStore insertion should succeed", insertedUri)

        // Pre-insert into memory repository with same URI
        val preExisting = MemoryItem(
            id = UUID.nameUUIDFromBytes(insertedUri.toString().toByteArray()).toString(),
            sourceType = SourceType.SCREENSHOT,
            title = "Already ingested",
            rawText = "Already ingested",
            sourceUri = insertedUri.toString()
        )
        memoryRepository.saveMemory(preExisting)

        // Run coordinator
        val ingested = coordinator.processPendingScreenshots()
        assertEquals("Coordinator should skip already ingested screenshot", 0, ingested.size)
        assertEquals("Memory count should remain 1", 1, memoryRepository.getMemoryCount().first())
    }
}
