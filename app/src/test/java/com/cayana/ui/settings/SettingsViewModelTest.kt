package com.cayana.ui.settings

import app.cash.turbine.test
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.LocalSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: LocalSettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = LocalSettingsRepository()
        viewModel = SettingsViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleSource updates enabled sources in state`() = runTest {
        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val initial = awaitItem()
            assertTrue(initial.settings.enabledSources.contains(SourceType.SCREENSHOT))

            viewModel.toggleSource(SourceType.SCREENSHOT, false)
            testDispatcher.scheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertFalse(updated.settings.enabledSources.contains(SourceType.SCREENSHOT))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleAutoCalendar updates auto calendar setting`() = runTest {
        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val initial = awaitItem()
            assertFalse(initial.settings.autoCalendarEnabled)

            viewModel.toggleAutoCalendar(true)
            testDispatcher.scheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertTrue(updated.settings.autoCalendarEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
