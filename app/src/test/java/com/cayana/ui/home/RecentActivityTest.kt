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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.calendar.data.CalendarActionEntity

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun deleteMemoryPreservesCalendarActionAndExternalEvent() = testScope.runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CayanaDatabase::class.java
        ).allowMainThreadQueries().build()

        val roomRepo = RoomMemoryRepository(
            memoryDao = db.memoryDao(),
            searchDao = db.searchDao(),
            searchIndexStateDao = db.searchIndexStateDao()
        )
        val roomViewModel = HomeViewModel(memoryRepository = roomRepo)

        val memoryId = "mem-cal-preserved"
        val memory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Doctor Appointment",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        roomRepo.saveMemory(memory)

        val calendarAction = CalendarActionEntity(
            id = "action-preserved-1",
            memoryId = memoryId,
            calendarId = 1L,
            calendarEventId = 99999L,
            actionType = "INSERT_EVENT",
            createdAt = 1000L,
            status = "COMPLETED",
            title = "Doctor Appointment",
            startAt = 2000L,
            endAt = 3000L
        )
        db.calendarActionDao().insert(calendarAction)

        // Delete memory via HomeViewModel
        roomViewModel.deleteMemory(memoryId)
        advanceUntilIdle()

        // Memory must be deleted
        val deletedMemory = roomRepo.getMemoryById(memoryId).first()
        assertNull("Memory must be deleted", deletedMemory)

        // CalendarAction and external event id MUST be preserved
        val preservedAction = db.calendarActionDao().getById("action-preserved-1")
        assertNotNull("Calendar action audit must NOT be deleted when memory is deleted", preservedAction)
        assertEquals(99999L, preservedAction?.calendarEventId)
        assertEquals("COMPLETED", preservedAction?.status)

        db.close()
    }
}
