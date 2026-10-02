package com.cayana.core.permission

import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.onboarding.OnboardingViewModel
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionHandlingTest {

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
    fun grantedPermissionReflectsEnabledAndAuthorized() = runTest {
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val screenshot = viewModel.uiState.value.sources.first { it.type == SourceType.SCREENSHOT }
        assertTrue(screenshot.isEnabled)
        assertEquals(SourceStatus.ENABLED_AND_AUTHORIZED, screenshot.status)
    }

    @Test
    fun deniedPermissionMarksSourceAsPermissionDeniedWithoutCrash() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onPermissionResult(SourceType.SCREENSHOT, isGranted = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val screenshot = viewModel.uiState.value.sources.first { it.type == SourceType.SCREENSHOT }
        assertEquals(SourceStatus.PERMISSION_DENIED, screenshot.status)
    }

    @Test
    fun sourceIndependenceDenyingPhotosDoesNotAffectScreenshots() = runTest {
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Enable Photos
        viewModel.toggleSource(SourceType.PHOTO, true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Deny Photos permission
        viewModel.onPermissionResult(SourceType.PHOTO, isGranted = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val photo = viewModel.uiState.value.sources.first { it.type == SourceType.PHOTO }
        val screenshot = viewModel.uiState.value.sources.first { it.type == SourceType.SCREENSHOT }

        // Photo is denied
        assertEquals(SourceStatus.PERMISSION_DENIED, photo.status)
        // Screenshot remains enabled and authorized
        assertTrue(screenshot.isEnabled)
        assertEquals(SourceStatus.ENABLED_AND_AUTHORIZED, screenshot.status)
    }

    @Test
    fun permissionRevokedTransitionsToPermissionRequiredGracefully() = runTest {
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val initialScreenshot = viewModel.uiState.value.sources.first { it.type == SourceType.SCREENSHOT }
        assertEquals(SourceStatus.ENABLED_AND_AUTHORIZED, initialScreenshot.status)

        // Simulate OS revoking permission in background
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_IMAGES", false)
        viewModel.refreshPermissions()
        testDispatcher.scheduler.advanceUntilIdle()

        val revokedScreenshot = viewModel.uiState.value.sources.first { it.type == SourceType.SCREENSHOT }
        assertEquals(SourceStatus.ENABLED_PERMISSION_REQUIRED, revokedScreenshot.status)
    }
}
