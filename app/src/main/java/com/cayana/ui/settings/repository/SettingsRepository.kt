package com.cayana.ui.settings.repository

import com.cayana.source.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SourceWatcherStatus {
    UNINITIALIZED,
    ACTIVE,
    DISABLED
}

typealias ScreenshotWatcherStatus = SourceWatcherStatus

/**
 * Model holding persisted user preferences.
 */
data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val enabledSources: Set<SourceType> = setOf(SourceType.SCREENSHOT),
    val selectedCalendarId: String? = null,
    val selectedCalendarName: String? = null,
    val autoCalendarEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val privateLoggingEnforced: Boolean = true,
    val isLocalFirstOnly: Boolean = true,
    val downloadsDirectoryUri: String? = null,
    val lastScreenshotMediaId: Long = 0L,
    val mediaStoreVersion: String? = null,
    val screenshotWatcherStatus: SourceWatcherStatus = SourceWatcherStatus.UNINITIALIZED,
    val lastPhotoMediaId: Long = 0L,
    val photoWatcherStatus: SourceWatcherStatus = SourceWatcherStatus.UNINITIALIZED,
    val lastRecordingMediaId: Long = 0L,
    val recordingWatcherStatus: SourceWatcherStatus = SourceWatcherStatus.UNINITIALIZED
)

interface SettingsRepository {
    fun getSettings(): Flow<UserSettings>
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun updateSourceEnabled(sourceType: SourceType, enabled: Boolean)
    suspend fun updateSelectedCalendar(calendarId: String?, calendarName: String?)
    suspend fun updateAutoCalendar(enabled: Boolean)
    suspend fun updateNotificationsEnabled(enabled: Boolean)
    suspend fun updateDownloadsDirectoryUri(uriString: String?)
    suspend fun updateLastScreenshotMediaId(id: Long)
    suspend fun updateScreenshotWatcherStatus(status: SourceWatcherStatus)
    suspend fun updateLastPhotoMediaId(id: Long)
    suspend fun updatePhotoWatcherStatus(status: SourceWatcherStatus)
    suspend fun updateLastRecordingMediaId(id: Long)
    suspend fun updateRecordingWatcherStatus(status: SourceWatcherStatus)
    suspend fun updateMediaStoreVersion(version: String?)
}

/**
 * In-memory implementation of SettingsRepository, ideal for unit testing and mocks.
 */
class InMemorySettingsRepository(
    initialSettings: UserSettings = UserSettings()
) : SettingsRepository {
    private val _settings = MutableStateFlow(initialSettings)

    override fun getSettings(): Flow<UserSettings> = _settings.asStateFlow()

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        _settings.update { it.copy(onboardingCompleted = completed) }
    }

    override suspend fun updateSourceEnabled(sourceType: SourceType, enabled: Boolean) {
        _settings.update { current ->
            val updatedSources = if (enabled) {
                current.enabledSources + sourceType
            } else {
                current.enabledSources - sourceType
            }
            current.copy(enabledSources = updatedSources)
        }
    }

    override suspend fun updateSelectedCalendar(calendarId: String?, calendarName: String?) {
        _settings.update {
            it.copy(selectedCalendarId = calendarId, selectedCalendarName = calendarName)
        }
    }

    override suspend fun updateAutoCalendar(enabled: Boolean) {
        _settings.update { it.copy(autoCalendarEnabled = enabled) }
    }

    override suspend fun updateNotificationsEnabled(enabled: Boolean) {
        _settings.update { it.copy(notificationsEnabled = enabled) }
    }

    override suspend fun updateDownloadsDirectoryUri(uriString: String?) {
        _settings.update { it.copy(downloadsDirectoryUri = uriString) }
    }

    override suspend fun updateLastScreenshotMediaId(id: Long) {
        _settings.update { it.copy(lastScreenshotMediaId = id) }
    }

    override suspend fun updateScreenshotWatcherStatus(status: SourceWatcherStatus) {
        _settings.update { it.copy(screenshotWatcherStatus = status) }
    }

    override suspend fun updateLastPhotoMediaId(id: Long) {
        _settings.update { it.copy(lastPhotoMediaId = id) }
    }

    override suspend fun updatePhotoWatcherStatus(status: SourceWatcherStatus) {
        _settings.update { it.copy(photoWatcherStatus = status) }
    }

    override suspend fun updateLastRecordingMediaId(id: Long) {
        _settings.update { it.copy(lastRecordingMediaId = id) }
    }

    override suspend fun updateRecordingWatcherStatus(status: SourceWatcherStatus) {
        _settings.update { it.copy(recordingWatcherStatus = status) }
    }

    override suspend fun updateMediaStoreVersion(version: String?) {
        _settings.update { it.copy(mediaStoreVersion = version) }
    }
}
