package com.cayana.ui.settings.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cayana.source.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPersistenceTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: CoroutineScope

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = CoroutineScope(testDispatcher + Job())
    }

    @After
    fun tearDown() {
        testScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun settingsPersistAcrossRepositoryRecreation() = runTest {
        val dataStoreFile = tmpFolder.newFile("test_settings.preferences_pb")

        val dataStore1 = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { dataStoreFile }
        )
        val repository1 = DataStoreSettingsRepository(dataStore1)

        // 1. Initial settings: Default Screenshot enabled, Photos disabled, onboarding not completed
        val initialSettings = repository1.getSettings().first()
        assertTrue(initialSettings.enabledSources.contains(SourceType.SCREENSHOT))
        assertFalse(initialSettings.enabledSources.contains(SourceType.PHOTO))
        assertFalse(initialSettings.onboardingCompleted)

        // 2. Modify settings
        repository1.updateSourceEnabled(SourceType.PHOTO, true)
        repository1.updateSourceEnabled(SourceType.RECORDING, true)
        repository1.updateSelectedCalendar("cal_work_456", "Work Calendar")
        repository1.updateAutoCalendar(true)
        repository1.setOnboardingCompleted(true)

        // Verify updated in repository1
        val updatedSettings = repository1.getSettings().first()
        assertTrue(updatedSettings.onboardingCompleted)
        assertTrue(updatedSettings.enabledSources.contains(SourceType.PHOTO))
        assertTrue(updatedSettings.enabledSources.contains(SourceType.RECORDING))
        assertEquals("cal_work_456", updatedSettings.selectedCalendarId)
        assertEquals("Work Calendar", updatedSettings.selectedCalendarName)
        assertTrue(updatedSettings.autoCalendarEnabled)

        // 3. Simulate process restart: Reconstruct repository from DataStore
        val repository2 = DataStoreSettingsRepository(dataStore1)

        // 4. Assert all settings survived process death / repository recreation
        val restoredSettings = repository2.getSettings().first()
        assertTrue(restoredSettings.onboardingCompleted)
        assertTrue(restoredSettings.enabledSources.contains(SourceType.SCREENSHOT))
        assertTrue(restoredSettings.enabledSources.contains(SourceType.PHOTO))
        assertTrue(restoredSettings.enabledSources.contains(SourceType.RECORDING))
        assertEquals("cal_work_456", restoredSettings.selectedCalendarId)
        assertEquals("Work Calendar", restoredSettings.selectedCalendarName)
        assertTrue(restoredSettings.autoCalendarEnabled)
    }
}
