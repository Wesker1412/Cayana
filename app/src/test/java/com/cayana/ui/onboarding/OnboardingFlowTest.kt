package com.cayana.ui.onboarding

import com.cayana.calendar.CalendarTarget
import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingFlowTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var calendarProviderHelper: FakeCalendarProviderHelper
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = InMemorySettingsRepository()
        permissionChecker = FakePermissionChecker()
        calendarProviderHelper = FakeCalendarProviderHelper()
        viewModel = OnboardingViewModel(
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialLaunchStartsAtWelcomeStep() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.currentStep)
        assertFalse(viewModel.uiState.value.isOnboardingCompleted)
    }

    @Test
    fun fullOnboardingStepNavigationAndCompletion() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.currentStep)

        // 1. Welcome -> Sources
        viewModel.nextStep()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.SOURCES, viewModel.uiState.value.currentStep)
        val screenshot = viewModel.uiState.value.sources.first { it.type == SourceType.SCREENSHOT }
        val photo = viewModel.uiState.value.sources.first { it.type == SourceType.PHOTO }
        assertTrue(screenshot.isEnabled)
        assertFalse(photo.isEnabled)

        // 2. Sources -> Calendar
        viewModel.nextStep()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.CALENDAR, viewModel.uiState.value.currentStep)

        // Select calendar target
        val target = CalendarTarget(id = "cal_personal", displayName = "Personal", isPrimary = true)
        viewModel.selectCalendar(target)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("cal_personal", viewModel.uiState.value.selectedCalendarId)

        // 3. Calendar -> Backup
        viewModel.nextStep()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.BACKUP, viewModel.uiState.value.currentStep)

        // 4. Backup -> Finish
        viewModel.nextStep()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.FINISH, viewModel.uiState.value.currentStep)

        // 5. Complete Onboarding
        viewModel.completeOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isOnboardingCompleted)

        // Verify persisted to repository
        val persisted = settingsRepository.getSettings().first()
        assertTrue(persisted.onboardingCompleted)
        assertEquals("cal_personal", persisted.selectedCalendarId)
    }

    @Test
    fun previousStepNavigatesBackCorrectly() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.currentStep)

        viewModel.nextStep() // To SOURCES
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.SOURCES, viewModel.uiState.value.currentStep)

        viewModel.nextStep() // To CALENDAR
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.CALENDAR, viewModel.uiState.value.currentStep)

        viewModel.previousStep() // Back to SOURCES
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.SOURCES, viewModel.uiState.value.currentStep)

        viewModel.previousStep() // Back to WELCOME
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.currentStep)
    }
}
