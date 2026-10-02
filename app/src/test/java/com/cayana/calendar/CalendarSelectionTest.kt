package com.cayana.calendar

import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.onboarding.OnboardingViewModel
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSelectionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var calendarProviderHelper: FakeCalendarProviderHelper

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = InMemorySettingsRepository()
        permissionChecker = FakePermissionChecker()
        calendarProviderHelper = FakeCalendarProviderHelper()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): OnboardingViewModel {
        return OnboardingViewModel(
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper
        )
    }

    @Test
    fun writableCalendarsAreLoadedAndSelected() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.calendars.size)
        // Default auto-selects primary
        assertEquals("1", viewModel.uiState.value.selectedCalendarId)

        // Select second calendar
        val workCalendar = viewModel.uiState.value.calendars.first { it.id == "2" }
        viewModel.selectCalendar(workCalendar)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("2", viewModel.uiState.value.selectedCalendarId)
        assertEquals("Work", viewModel.uiState.value.selectedCalendarName)

        val persisted = settingsRepository.getSettings().first()
        assertEquals("2", persisted.selectedCalendarId)
        assertEquals("Work", persisted.selectedCalendarName)
    }

    @Test
    fun noWritableCalendarsGracefullyHandledAndCanBeSkipped() = runTest {
        calendarProviderHelper.availableCalendars = emptyList()
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.calendars.isEmpty())
        assertNull(viewModel.uiState.value.selectedCalendarId)

        viewModel.skipCalendar()
        testDispatcher.scheduler.advanceUntilIdle()

        val persisted = settingsRepository.getSettings().first()
        assertNull(persisted.selectedCalendarId)
    }

    @Test
    fun permissionDeniedDoesNotCrashAndAllowsSkip() = runTest {
        calendarProviderHelper.hasPermission = false
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.calendars.isEmpty())

        viewModel.skipCalendar()
        testDispatcher.scheduler.advanceUntilIdle()

        val persisted = settingsRepository.getSettings().first()
        assertNull(persisted.selectedCalendarId)
    }
}
