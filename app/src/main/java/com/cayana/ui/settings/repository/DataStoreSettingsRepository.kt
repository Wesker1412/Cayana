package com.cayana.ui.settings.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cayana.source.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Production implementation of SettingsRepository using Jetpack Preferences DataStore.
 * Ensures user settings survive process death, device reboots, and app updates.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    companion object {
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_ENABLED_SOURCES = stringSetPreferencesKey("enabled_sources")
        val KEY_SELECTED_CALENDAR_ID = stringPreferencesKey("selected_calendar_id")
        val KEY_SELECTED_CALENDAR_NAME = stringPreferencesKey("selected_calendar_name")
        val KEY_AUTO_CALENDAR_ENABLED = booleanPreferencesKey("auto_calendar_enabled")
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_PRIVATE_LOGGING = booleanPreferencesKey("private_logging_enforced")
        val KEY_LOCAL_FIRST = booleanPreferencesKey("is_local_first_only")
        val KEY_DOWNLOADS_DIRECTORY_URI = stringPreferencesKey("downloads_directory_uri")
        val KEY_LAST_SCREENSHOT_MEDIA_ID = longPreferencesKey("last_screenshot_media_id")
    }

    override fun getSettings(): Flow<UserSettings> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                val onboardingCompleted = preferences[KEY_ONBOARDING_COMPLETED] ?: false

                val rawSources = preferences[KEY_ENABLED_SOURCES]
                val enabledSources = if (rawSources != null) {
                    rawSources.mapNotNull { name ->
                        try {
                            SourceType.valueOf(name)
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    }.toSet()
                } else {
                    setOf(SourceType.SCREENSHOT)
                }

                val calendarId = preferences[KEY_SELECTED_CALENDAR_ID]
                val calendarName = preferences[KEY_SELECTED_CALENDAR_NAME]
                val autoCalendar = preferences[KEY_AUTO_CALENDAR_ENABLED] ?: false
                val notifications = preferences[KEY_NOTIFICATIONS_ENABLED] ?: true
                val privateLogging = preferences[KEY_PRIVATE_LOGGING] ?: true
                val localFirst = preferences[KEY_LOCAL_FIRST] ?: true
                val downloadsUri = preferences[KEY_DOWNLOADS_DIRECTORY_URI]
                val lastMediaId = preferences[KEY_LAST_SCREENSHOT_MEDIA_ID] ?: 0L

                UserSettings(
                    onboardingCompleted = onboardingCompleted,
                    enabledSources = enabledSources,
                    selectedCalendarId = calendarId,
                    selectedCalendarName = calendarName,
                    autoCalendarEnabled = autoCalendar,
                    notificationsEnabled = notifications,
                    privateLoggingEnforced = privateLogging,
                    isLocalFirstOnly = localFirst,
                    downloadsDirectoryUri = downloadsUri,
                    lastScreenshotMediaId = lastMediaId
                )
            }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    override suspend fun updateSourceEnabled(sourceType: SourceType, enabled: Boolean) {
        dataStore.edit { preferences ->
            val currentSources = preferences[KEY_ENABLED_SOURCES]?.toMutableSet()
                ?: mutableSetOf(SourceType.SCREENSHOT.name)

            if (enabled) {
                currentSources.add(sourceType.name)
            } else {
                currentSources.remove(sourceType.name)
            }
            preferences[KEY_ENABLED_SOURCES] = currentSources
        }
    }

    override suspend fun updateSelectedCalendar(calendarId: String?, calendarName: String?) {
        dataStore.edit { preferences ->
            if (calendarId != null) {
                preferences[KEY_SELECTED_CALENDAR_ID] = calendarId
            } else {
                preferences.remove(KEY_SELECTED_CALENDAR_ID)
            }

            if (calendarName != null) {
                preferences[KEY_SELECTED_CALENDAR_NAME] = calendarName
            } else {
                preferences.remove(KEY_SELECTED_CALENDAR_NAME)
            }
        }
    }

    override suspend fun updateAutoCalendar(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_AUTO_CALENDAR_ENABLED] = enabled
        }
    }

    override suspend fun updateNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATIONS_ENABLED] = enabled
        }
    }

    override suspend fun updateDownloadsDirectoryUri(uriString: String?) {
        dataStore.edit { preferences ->
            if (uriString != null) {
                preferences[KEY_DOWNLOADS_DIRECTORY_URI] = uriString
            } else {
                preferences.remove(KEY_DOWNLOADS_DIRECTORY_URI)
            }
        }
    }

    override suspend fun updateLastScreenshotMediaId(id: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_SCREENSHOT_MEDIA_ID] = id
        }
    }
}
