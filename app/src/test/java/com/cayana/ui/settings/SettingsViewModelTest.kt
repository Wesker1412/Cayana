package com.cayana.ui.settings

import app.cash.turbine.test
import com.cayana.calendar.CalendarTarget
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var calendarProviderHelper: FakeCalendarProviderHelper
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = InMemorySettingsRepository()
        permissionChecker = FakePermissionChecker()
        calendarProviderHelper = FakeCalendarProviderHelper()
        viewModel = SettingsViewModel(
            settingsRepository = repository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper
        )
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

    @Test
    fun `selectCalendar and clearCalendar updates calendar settings`() = runTest {
        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            awaitItem()

            val target = CalendarTarget(id = "cal_123", displayName = "Personal Calendar", isPrimary = true)
            viewModel.selectCalendar(target)
            testDispatcher.scheduler.advanceUntilIdle()

            val selected = awaitItem()
            assertEquals("cal_123", selected.settings.selectedCalendarId)
            assertEquals("Personal Calendar", selected.settings.selectedCalendarName)

            viewModel.clearCalendar()
            testDispatcher.scheduler.advanceUntilIdle()

            val cleared = awaitItem()
            assertNull(cleared.settings.selectedCalendarId)
            assertNull(cleared.settings.selectedCalendarName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permission denial marks source status as PERMISSION_DENIED`() = runTest {
        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            awaitItem()

            viewModel.onPermissionResult(SourceType.SCREENSHOT, isGranted = false)
            testDispatcher.scheduler.advanceUntilIdle()

            val deniedState = awaitItem()
            val screenshotItem = deniedState.sourceItems.first { it.type == SourceType.SCREENSHOT }
            assertEquals(SourceStatus.PERMISSION_DENIED, screenshotItem.status)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
