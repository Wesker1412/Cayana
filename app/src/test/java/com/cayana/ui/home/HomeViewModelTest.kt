package com.cayana.ui.home

import app.cash.turbine.test
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeMemoryRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeMemoryRepository()
        viewModel = HomeViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState reflects empty repository`() = runTest {
        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(0, state.totalCount)
            assertEquals(emptyList<MemoryItem>(), state.memories)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addSampleMemory updates uiState through Flow`() = runTest {
        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(0, initial.totalCount)

            val item = MemoryItem(
                sourceType = SourceType.SCREENSHOT,
                title = "Live Concert",
                rawText = "10/18 Live Taipei"
            )
            viewModel.addSampleMemory(item)
            testDispatcher.scheduler.advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertEquals(1, updated.totalCount)
            assertEquals(1, updated.memories.size)
            assertEquals("Live Concert", updated.memories.first().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search query filters displayed memories`() = runTest {
        val item1 = MemoryItem(sourceType = SourceType.SCREENSHOT, title = "Apple Store receipt")
        val item2 = MemoryItem(sourceType = SourceType.PHOTO, title = "Coffee shop photo")
        repository.saveMemory(item1)
        repository.saveMemory(item2)

        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(2, initial.memories.size)

            viewModel.onSearchQueryChanged("Apple")
            testDispatcher.scheduler.advanceUntilIdle()

            val filtered = expectMostRecentItem()
            assertEquals(1, filtered.memories.size)
            assertEquals("Apple Store receipt", filtered.memories.first().title)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
