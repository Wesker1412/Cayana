package com.cayana.ui.home

import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.search.SearchFilterCategory
import com.cayana.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentActivityTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var repository: FakeMemoryRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeMemoryRepository()
        viewModel = HomeViewModel(
            memoryRepository = repository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recentMemoriesSortedNewestFirst() = testScope.runTest {
        val oldMemory = MemoryItem(
            id = "mem-old",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Older Item",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        val newMemory = MemoryItem(
            id = "mem-new",
            sourceType = SourceType.PHOTO,
            createdAt = 5000L,
            capturedAt = 5000L,
            title = "Newer Item",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        val middleMemory = MemoryItem(
            id = "mem-mid",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 3000L,
            capturedAt = 3000L,
            title = "Middle Item",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )

        repository.saveMemory(oldMemory)
        repository.saveMemory(newMemory)
        repository.saveMemory(middleMemory)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.memories.size)
        // Order by capturedAt descending: new (5000) -> mid (3000) -> old (1000)
        assertEquals("mem-new", state.memories[0].id)
        assertEquals("mem-mid", state.memories[1].id)
        assertEquals("mem-old", state.memories[2].id)

        collectJob.cancel()
    }

    @Test
    fun recentContainsAllSourceTypes() = testScope.runTest {
        val types = listOf(
            SourceType.SCREENSHOT,
            SourceType.PHOTO,
            SourceType.RECORDING,
            SourceType.SHARED_TEXT,
            SourceType.SHARED_URL,
            SourceType.SHARED_IMAGE,
            SourceType.SHARED_DOCUMENT
        )

        types.forEachIndexed { index, type ->
            repository.saveMemory(
                MemoryItem(
                    id = "item-$index",
                    sourceType = type,
                    createdAt = (index + 1) * 1000L,
                    capturedAt = (index + 1) * 1000L,
                    title = "Title $type",
                    sourceExists = true,
                    processingState = ProcessingState.COMPLETED
                )
            )
        }

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(types.size, state.memories.size)
        val presentTypes = state.memories.map { it.sourceType }.toSet()
        assertEquals(types.toSet(), presentTypes)

        // Verify SHARED filter isolates all shared types
        viewModel.onFilterCategoryChanged(SearchFilterCategory.SHARED)
        advanceUntilIdle()

        val sharedState = viewModel.uiState.value
        val sharedItems = sharedState.memories
        assertEquals(4, sharedItems.size) // TEXT, URL, IMAGE, DOCUMENT
        assertTrue(sharedItems.all { it.sourceType.isShared })

        collectJob.cancel()
    }

    @Test
    fun processingMemoryStillAppears() = testScope.runTest {
        val pendingMemory = MemoryItem(
            id = "pending-1",
            sourceType = SourceType.RECORDING,
            createdAt = 2000L,
            capturedAt = 2000L,
            title = "語音辨識中",
            sourceExists = true,
            processingState = ProcessingState.PENDING
        )
        repository.saveMemory(pendingMemory)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.memories.size)
        val item = state.memories.first()
        assertEquals(ProcessingState.PENDING, item.processingState)

        collectJob.cancel()
    }

    @Test
    fun missingSourceMemoryStillAppears() = testScope.runTest {
        val missingSourceMemory = MemoryItem(
            id = "missing-1",
            sourceType = SourceType.PHOTO,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "已刪除的原始照片",
            rawText = "OCR 取得的收據文字依然存在",
            sourceExists = false,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(missingSourceMemory)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.memories.size)
        val item = state.memories.first()
        assertFalse("sourceExists must be false", item.sourceExists)
        assertEquals("OCR 取得的收據文字依然存在", item.rawText)

        collectJob.cancel()
    }

    @Test
    fun deleteMemoryRemovesMemoryAndClearsDetail() = testScope.runTest {
        val item = MemoryItem(
            id = "del-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "即將刪除",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(item)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onSelectMemoryForDetail(item)
        advanceUntilIdle()
        assertEquals("del-1", viewModel.uiState.value.selectedDetailMemory?.id)

        // Delete memory
        viewModel.deleteMemory("del-1")
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.memories.size)
        assertNull("selectedDetailMemory must be cleared when deleted", viewModel.uiState.value.selectedDetailMemory)

        collectJob.cancel()
    }
}
