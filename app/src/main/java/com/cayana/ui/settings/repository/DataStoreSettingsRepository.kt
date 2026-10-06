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
        val KEY_SCREENSHOT_WATCHER_STATUS = stringPreferencesKey("screenshot_watcher_status")
        val KEY_LAST_PHOTO_MEDIA_ID = longPreferencesKey("last_photo_media_id")
        val KEY_PHOTO_WATCHER_STATUS = stringPreferencesKey("photo_watcher_status")
        val KEY_LAST_RECORDING_MEDIA_ID = longPreferencesKey("last_recording_media_id")
        val KEY_RECORDING_WATCHER_STATUS = stringPreferencesKey("recording_watcher_status")
        val KEY_MEDIA_STORE_VERSION = stringPreferencesKey("media_store_version")
        val KEY_SCREENSHOT_MEDIA_STORE_VERSION = stringPreferencesKey("screenshot_media_store_version")
        val KEY_PHOTO_MEDIA_STORE_VERSION = stringPreferencesKey("photo_media_store_version")
        val KEY_RECORDING_MEDIA_STORE_VERSION = stringPreferencesKey("recording_media_store_version")
        val KEY_STT_MODEL_ID = stringPreferencesKey("stt_model_id")
        val KEY_STT_MODEL_VERSION = stringPreferencesKey("stt_model_version")
        val KEY_STT_MODEL_SHA256 = stringPreferencesKey("stt_model_sha256")
        val KEY_VERIFIED_MODEL_SHA256 = stringPreferencesKey("verified_model_sha256")
        val KEY_VERIFIED_TOKENS_SHA256 = stringPreferencesKey("verified_tokens_sha256")
        val KEY_STT_MODEL_FILE_SIZE = longPreferencesKey("stt_model_file_size")
        val KEY_STT_MODEL_LAST_MODIFIED = longPreferencesKey("stt_model_last_modified")
        val KEY_STT_MODEL_INSTALLED_AT = longPreferencesKey("stt_model_installed_at")
        val KEY_LAST_PHOTO_RECONCILED_AT = longPreferencesKey("last_photo_reconciled_captured_at")
        val KEY_LAST_PHOTO_RECONCILED_ID = stringPreferencesKey("last_photo_reconciled_memory_id")
        val KEY_LAST_RECORDING_RECONCILED_AT = longPreferencesKey("last_recording_reconciled_captured_at")
        val KEY_LAST_RECORDING_RECONCILED_ID = stringPreferencesKey("last_recording_reconciled_memory_id")
        val KEY_DRIVE_AUTH_STATUS = stringPreferencesKey("drive_auth_status")
        val KEY_AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val KEY_LAST_BACKUP_TIMESTAMP = longPreferencesKey("last_backup_timestamp")
        val KEY_HAS_RECOVERY_KEY = booleanPreferencesKey("has_recovery_key")
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
                val screenshotMediaVersion = preferences[KEY_SCREENSHOT_MEDIA_STORE_VERSION] ?: preferences[KEY_MEDIA_STORE_VERSION]
                val photoMediaVersion = preferences[KEY_PHOTO_MEDIA_STORE_VERSION]
                val recordingMediaVersion = preferences[KEY_RECORDING_MEDIA_STORE_VERSION]
                val rawWatcherStatus = preferences[KEY_SCREENSHOT_WATCHER_STATUS]
                val watcherStatus = if (rawWatcherStatus != null) {
                    try {
                        SourceWatcherStatus.valueOf(rawWatcherStatus)
                    } catch (_: Exception) {
                        SourceWatcherStatus.UNINITIALIZED
                    }
                } else {
                    SourceWatcherStatus.UNINITIALIZED
                }

                val lastPhotoId = preferences[KEY_LAST_PHOTO_MEDIA_ID] ?: 0L
                val rawPhotoStatus = preferences[KEY_PHOTO_WATCHER_STATUS]
                val photoStatus = if (rawPhotoStatus != null) {
                    try {
                        SourceWatcherStatus.valueOf(rawPhotoStatus)
                    } catch (_: Exception) {
                        SourceWatcherStatus.UNINITIALIZED
                    }
                } else {
                    SourceWatcherStatus.UNINITIALIZED
                }

                val lastRecordingId = preferences[KEY_LAST_RECORDING_MEDIA_ID] ?: 0L
                val rawRecordingStatus = preferences[KEY_RECORDING_WATCHER_STATUS]
                val recordingStatus = if (rawRecordingStatus != null) {
                    try {
                        SourceWatcherStatus.valueOf(rawRecordingStatus)
                    } catch (_: Exception) {
                        SourceWatcherStatus.UNINITIALIZED
                    }
                } else {
                    SourceWatcherStatus.UNINITIALIZED
                }

                val sttModelId = preferences[KEY_STT_MODEL_ID]
                val sttModelVersion = preferences[KEY_STT_MODEL_VERSION]
                val sttModelSha256 = preferences[KEY_STT_MODEL_SHA256]
                val verifiedModelSha256 = preferences[KEY_VERIFIED_MODEL_SHA256] ?: sttModelSha256
                val verifiedTokensSha256 = preferences[KEY_VERIFIED_TOKENS_SHA256]
                val sttModelFileSize = preferences[KEY_STT_MODEL_FILE_SIZE] ?: 0L
                val sttModelLastModified = preferences[KEY_STT_MODEL_LAST_MODIFIED] ?: 0L
                val sttModelInstalledAt = preferences[KEY_STT_MODEL_INSTALLED_AT] ?: 0L
                val lastPhotoReconciled = preferences[KEY_LAST_PHOTO_RECONCILED_AT] ?: Long.MAX_VALUE
                val lastPhotoReconciledId = preferences[KEY_LAST_PHOTO_RECONCILED_ID] ?: ""
                val lastRecordingReconciled = preferences[KEY_LAST_RECORDING_RECONCILED_AT] ?: Long.MAX_VALUE
                val lastRecordingReconciledId = preferences[KEY_LAST_RECORDING_RECONCILED_ID] ?: ""

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
                    lastScreenshotMediaId = lastMediaId,
                    mediaStoreVersion = screenshotMediaVersion,
                    screenshotMediaStoreVersion = screenshotMediaVersion,
                    screenshotWatcherStatus = watcherStatus,
                    lastPhotoMediaId = lastPhotoId,
                    photoMediaStoreVersion = photoMediaVersion,
                    photoWatcherStatus = photoStatus,
                    lastRecordingMediaId = lastRecordingId,
                    recordingMediaStoreVersion = recordingMediaVersion,
                    recordingWatcherStatus = recordingStatus,
                    sttModelId = sttModelId,
                    sttModelVersion = sttModelVersion,
                    sttModelSha256 = sttModelSha256,
                    sttModelInstalledAt = sttModelInstalledAt,
                    verifiedModelSha256 = verifiedModelSha256,
                    verifiedTokensSha256 = verifiedTokensSha256,
                    sttModelFileSize = sttModelFileSize,
                    sttModelLastModified = sttModelLastModified,
                    lastPhotoReconciledCapturedAt = lastPhotoReconciled,
                    lastPhotoReconciledMemoryId = lastPhotoReconciledId,
                    lastRecordingReconciledCapturedAt = lastRecordingReconciled,
                    lastRecordingReconciledMemoryId = lastRecordingReconciledId,
                    isDriveBackupConnected = (preferences[KEY_DRIVE_AUTH_STATUS] == com.cayana.backup.drive.DriveAuthStatus.CONNECTED.name),
                    driveAuthStatus = runCatching {
                        com.cayana.backup.drive.DriveAuthStatus.valueOf(preferences[KEY_DRIVE_AUTH_STATUS] ?: "")
                    }.getOrDefault(com.cayana.backup.drive.DriveAuthStatus.DISCONNECTED),
                    autoBackupEnabled = preferences[KEY_AUTO_BACKUP_ENABLED] ?: false,
                    lastBackupTimestamp = preferences[KEY_LAST_BACKUP_TIMESTAMP] ?: 0L,
                    hasRecoveryKey = preferences[KEY_HAS_RECOVERY_KEY] ?: false
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

    override suspend fun updateScreenshotWatcherStatus(status: SourceWatcherStatus) {
        dataStore.edit { preferences ->
            preferences[KEY_SCREENSHOT_WATCHER_STATUS] = status.name
        }
    }

    override suspend fun updateLastPhotoMediaId(id: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_PHOTO_MEDIA_ID] = id
        }
    }

    override suspend fun updatePhotoWatcherStatus(status: SourceWatcherStatus) {
        dataStore.edit { preferences ->
            preferences[KEY_PHOTO_WATCHER_STATUS] = status.name
        }
    }

    override suspend fun updateLastRecordingMediaId(id: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_RECORDING_MEDIA_ID] = id
        }
    }

    override suspend fun updateRecordingWatcherStatus(status: SourceWatcherStatus) {
        dataStore.edit { preferences ->
            preferences[KEY_RECORDING_WATCHER_STATUS] = status.name
        }
    }

    override suspend fun updateMediaStoreVersion(version: String?) {
        updateScreenshotMediaStoreVersion(version)
    }

    override suspend fun updateScreenshotMediaStoreVersion(version: String?) {
        dataStore.edit { preferences ->
            if (version != null) {
                preferences[KEY_SCREENSHOT_MEDIA_STORE_VERSION] = version
                preferences[KEY_MEDIA_STORE_VERSION] = version
            } else {
                preferences.remove(KEY_SCREENSHOT_MEDIA_STORE_VERSION)
                preferences.remove(KEY_MEDIA_STORE_VERSION)
            }
        }
    }

    override suspend fun updatePhotoMediaStoreVersion(version: String?) {
        dataStore.edit { preferences ->
            if (version != null) {
                preferences[KEY_PHOTO_MEDIA_STORE_VERSION] = version
            } else {
                preferences.remove(KEY_PHOTO_MEDIA_STORE_VERSION)
            }
        }
    }

    override suspend fun updateRecordingMediaStoreVersion(version: String?) {
        dataStore.edit { preferences ->
            if (version != null) {
                preferences[KEY_RECORDING_MEDIA_STORE_VERSION] = version
            } else {
                preferences.remove(KEY_RECORDING_MEDIA_STORE_VERSION)
            }
        }
    }

    override suspend fun updateSttModelInfo(
        modelId: String?,
        modelVersion: String?,
        modelSha256: String?,
        installedAt: Long
    ) {
        dataStore.edit { preferences ->
            if (modelId != null) preferences[KEY_STT_MODEL_ID] = modelId else preferences.remove(KEY_STT_MODEL_ID)
            if (modelVersion != null) preferences[KEY_STT_MODEL_VERSION] = modelVersion else preferences.remove(KEY_STT_MODEL_VERSION)
            if (modelSha256 != null) preferences[KEY_STT_MODEL_SHA256] = modelSha256 else preferences.remove(KEY_STT_MODEL_SHA256)
            preferences[KEY_STT_MODEL_INSTALLED_AT] = installedAt
        }
    }

    override suspend fun updateLastPhotoReconciledCapturedAt(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_PHOTO_RECONCILED_AT] = timestamp
        }
    }

    override suspend fun updateLastRecordingReconciledCapturedAt(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_RECORDING_RECONCILED_AT] = timestamp
        }
    }

    override suspend fun updatePhotoReconcileCursor(capturedAt: Long, memoryId: String) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_PHOTO_RECONCILED_AT] = capturedAt
            preferences[KEY_LAST_PHOTO_RECONCILED_ID] = memoryId
        }
    }

    override suspend fun resetPhotoReconcileCursor() {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_PHOTO_RECONCILED_AT] = Long.MAX_VALUE
            preferences[KEY_LAST_PHOTO_RECONCILED_ID] = ""
        }
    }

    override suspend fun updateRecordingReconcileCursor(capturedAt: Long, memoryId: String) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_RECORDING_RECONCILED_AT] = capturedAt
            preferences[KEY_LAST_RECORDING_RECONCILED_ID] = memoryId
        }
    }

    override suspend fun resetRecordingReconcileCursor() {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_RECORDING_RECONCILED_AT] = Long.MAX_VALUE
            preferences[KEY_LAST_RECORDING_RECONCILED_ID] = ""
        }
    }

    override suspend fun updateVerifiedSttModel(
        modelId: String,
        modelVersion: String,
        modelSha256: String,
        tokensSha256: String,
        fileSize: Long,
        lastModified: Long
    ) {
        dataStore.edit { preferences ->
            preferences[KEY_STT_MODEL_ID] = modelId
            preferences[KEY_STT_MODEL_VERSION] = modelVersion
            preferences[KEY_STT_MODEL_SHA256] = modelSha256
            preferences[KEY_VERIFIED_MODEL_SHA256] = modelSha256
            preferences[KEY_VERIFIED_TOKENS_SHA256] = tokensSha256
            preferences[KEY_STT_MODEL_FILE_SIZE] = fileSize
            preferences[KEY_STT_MODEL_LAST_MODIFIED] = lastModified
            preferences[KEY_STT_MODEL_INSTALLED_AT] = System.currentTimeMillis()
        }
    }

    override suspend fun clearSttModelMetadata() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_STT_MODEL_ID)
            preferences.remove(KEY_STT_MODEL_VERSION)
            preferences.remove(KEY_STT_MODEL_SHA256)
            preferences.remove(KEY_VERIFIED_MODEL_SHA256)
            preferences.remove(KEY_VERIFIED_TOKENS_SHA256)
            preferences.remove(KEY_STT_MODEL_FILE_SIZE)
            preferences.remove(KEY_STT_MODEL_LAST_MODIFIED)
            preferences.remove(KEY_STT_MODEL_INSTALLED_AT)
        }
    }

    override suspend fun updateDriveAuthStatus(status: com.cayana.backup.drive.DriveAuthStatus) {
        dataStore.edit { preferences ->
            preferences[KEY_DRIVE_AUTH_STATUS] = status.name
        }
    }

    override suspend fun updateAutoBackupEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_AUTO_BACKUP_ENABLED] = enabled
        }
    }

    override suspend fun updateLastBackupTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_BACKUP_TIMESTAMP] = timestamp
        }
    }

    override suspend fun updateHasRecoveryKey(hasKey: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_HAS_RECOVERY_KEY] = hasKey
        }
    }
}
