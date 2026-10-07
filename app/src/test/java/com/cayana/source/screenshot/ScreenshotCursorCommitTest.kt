package com.cayana.source.screenshot

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.memory.repository.MutationOrigin
import com.cayana.processing.OcrResult
import com.cayana.test.FakeMediaContentProvider
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.ScreenshotWatcherStatus
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotCursorCommitTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FaultInjectableMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var ocrEngine: FakeOcrEngine
    private lateinit var coordinator: ScreenshotProcessingCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaContentProvider.register(context)
        memoryRepository = FaultInjectableMemoryRepository()
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(screenshotWatcherStatus = ScreenshotWatcherStatus.ACTIVE)
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        ocrEngine = FakeOcrEngine(Result.Success(OcrResult(fullText = "Fault Injection Test")))
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
    fun `cursor does not advance past failed Room write and retries on subsequent run`() = runTest {
        val id1 = insertMockScreenshot("Screenshot_success_1.png")
        val id2 = insertMockScreenshot("Screenshot_fail_2.png")
        val id3 = insertMockScreenshot("Screenshot_pending_3.png")

        // Configure repository to fail when attempting to save id2
        val id2Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id2).toString()
        memoryRepository.failOnUri = id2Uri

        val firstRunMemories = coordinator.processPendingScreenshots()
        assertEquals("Only id1 should be successfully ingested", 1, firstRunMemories.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())

        val cursorAfterFailure = settingsRepository.getSettings().first().lastScreenshotMediaId
        assertEquals("Cursor must ONLY advance up to id1 and NOT past the failed id2", id1, cursorAfterFailure)

        // Clear fault injection, simulating database recovery / retry
        memoryRepository.failOnUri = null

        val secondRunMemories = coordinator.processPendingScreenshots()
        assertEquals("Subsequent run must retry from id2 and ingest both id2 and id3", 2, secondRunMemories.size)
        assertEquals(3, memoryRepository.getMemoryCount().first())

        val cursorAfterRetry = settingsRepository.getSettings().first().lastScreenshotMediaId
        assertEquals("Cursor must now advance to id3 after retry succeeds", id3, cursorAfterRetry)
    }

    private class FaultInjectableMemoryRepository : MemoryRepository {
        private val storage = mutableMapOf<String, MemoryItem>()
        private val countFlow = MutableStateFlow(0)
        var failOnUri: String? = null

        override fun getAllMemories(): Flow<List<MemoryItem>> = MutableStateFlow(storage.values.toList())
        override fun getMemoryById(id: String): Flow<MemoryItem?> = MutableStateFlow(storage[id])
        override suspend fun getMemoryBySourceUri(sourceUri: String): MemoryItem? = storage.values.find { it.sourceUri == sourceUri }
        
        override suspend fun saveMemory(
            item: MemoryItem,
            origin: MutationOrigin
        ) {
            if (failOnUri != null && item.sourceUri == failOnUri) {
                throw IOException("Disk I/O Error: SQLite database locked")
            }
            storage[item.id] = item
            countFlow.value = storage.size
        }

        override suspend fun deleteMemory(
            id: String,
            origin: MutationOrigin
        ) {
            storage.remove(id)
            countFlow.value = storage.size
        }

        override fun searchMemories(query: String): Flow<List<MemoryItem>> = MutableStateFlow(emptyList())
        override fun getMemoryCount(): Flow<Int> = countFlow
        override suspend fun markSourceExists(id: String, exists: Boolean) {}
        override suspend fun getMemoriesForReconciliation(
            sourceType: com.cayana.source.SourceType,
            cursorTimestamp: Long,
            limit: Int
        ): List<MemoryItem> = storage.values
            .filter { it.sourceType == sourceType && it.sourceExists }
            .sortedByDescending { it.capturedAt }
            .take(limit)
        override suspend fun getMemoriesForCompoundReconciliation(
            sourceType: com.cayana.source.SourceType,
            cursorCapturedAt: Long,
            cursorId: String,
            limit: Int
        ): List<MemoryItem> = storage.values
            .filter { it.sourceType == sourceType && it.sourceExists }
            .filter { it.capturedAt < cursorCapturedAt || (it.capturedAt == cursorCapturedAt && it.id < cursorId) }
            .sortedWith(compareByDescending<MemoryItem> { it.capturedAt }.thenByDescending { it.id })
            .take(limit)
        override suspend fun clearAll() {
            storage.clear()
            countFlow.value = 0
        }
        override suspend fun rebuildSearchIndex() {}
        override fun isIndexRebuildNeeded(): Boolean = false
    }
}
