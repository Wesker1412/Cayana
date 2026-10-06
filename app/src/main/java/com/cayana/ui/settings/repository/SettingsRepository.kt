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
    val screenshotMediaStoreVersion: String? = mediaStoreVersion,
    val screenshotWatcherStatus: SourceWatcherStatus = SourceWatcherStatus.UNINITIALIZED,
    val lastPhotoMediaId: Long = 0L,
    val photoMediaStoreVersion: String? = null,
    val photoWatcherStatus: SourceWatcherStatus = SourceWatcherStatus.UNINITIALIZED,
    val lastRecordingMediaId: Long = 0L,
    val recordingMediaStoreVersion: String? = null,
    val recordingWatcherStatus: SourceWatcherStatus = SourceWatcherStatus.UNINITIALIZED,
    val sttModelId: String? = null,
    val sttModelVersion: String? = null,
    val sttModelSha256: String? = null,
    val sttModelInstalledAt: Long = 0L,
    val verifiedModelSha256: String? = sttModelSha256,
    val verifiedTokensSha256: String? = null,
    val sttModelFileSize: Long = 0L,
    val sttModelLastModified: Long = 0L,
    val lastPhotoReconciledCapturedAt: Long = Long.MAX_VALUE,
    val lastPhotoReconciledMemoryId: String = "",
    val lastRecordingReconciledCapturedAt: Long = Long.MAX_VALUE,
    val lastRecordingReconciledMemoryId: String = "",
    val isDriveBackupConnected: Boolean = false,
    val driveAuthStatus: com.cayana.backup.drive.DriveAuthStatus = com.cayana.backup.drive.DriveAuthStatus.DISCONNECTED,
    val autoBackupEnabled: Boolean = false,
    val lastBackupTimestamp: Long = 0L,
    val hasRecoveryKey: Boolean = false
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
    suspend fun updateScreenshotMediaStoreVersion(version: String?)
    suspend fun updatePhotoMediaStoreVersion(version: String?)
    suspend fun updateRecordingMediaStoreVersion(version: String?)
    suspend fun updateSttModelInfo(modelId: String?, modelVersion: String?, modelSha256: String?, installedAt: Long)
    suspend fun updateVerifiedSttModel(
        modelId: String,
        modelVersion: String,
        modelSha256: String,
        tokensSha256: String,
        fileSize: Long,
        lastModified: Long
    )
    suspend fun clearSttModelMetadata()
    suspend fun updateLastPhotoReconciledCapturedAt(timestamp: Long)
    suspend fun updateLastRecordingReconciledCapturedAt(timestamp: Long)
    suspend fun updatePhotoReconcileCursor(capturedAt: Long, memoryId: String)
    suspend fun resetPhotoReconcileCursor()
    suspend fun updateRecordingReconcileCursor(capturedAt: Long, memoryId: String)
    suspend fun resetRecordingReconcileCursor()
    suspend fun updateDriveAuthStatus(status: com.cayana.backup.drive.DriveAuthStatus)
    suspend fun updateAutoBackupEnabled(enabled: Boolean)
    suspend fun updateLastBackupTimestamp(timestamp: Long)
    suspend fun updateHasRecoveryKey(hasKey: Boolean)
    suspend fun applyPortableSettings(portable: com.cayana.backup.snapshot.PortableUserSettings)
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
        updateScreenshotMediaStoreVersion(version)
    }

    override suspend fun updateScreenshotMediaStoreVersion(version: String?) {
        _settings.update { it.copy(screenshotMediaStoreVersion = version, mediaStoreVersion = version) }
    }

    override suspend fun updatePhotoMediaStoreVersion(version: String?) {
        _settings.update { it.copy(photoMediaStoreVersion = version) }
    }

    override suspend fun updateRecordingMediaStoreVersion(version: String?) {
        _settings.update { it.copy(recordingMediaStoreVersion = version) }
    }

    override suspend fun updateSttModelInfo(
        modelId: String?,
        modelVersion: String?,
        modelSha256: String?,
        installedAt: Long
    ) {
        _settings.update {
            it.copy(
                sttModelId = modelId,
                sttModelVersion = modelVersion,
                sttModelSha256 = modelSha256,
                sttModelInstalledAt = installedAt
            )
        }
    }

    override suspend fun updateLastPhotoReconciledCapturedAt(timestamp: Long) {
        _settings.update { it.copy(lastPhotoReconciledCapturedAt = timestamp) }
    }

    override suspend fun updateLastRecordingReconciledCapturedAt(timestamp: Long) {
        _settings.update { it.copy(lastRecordingReconciledCapturedAt = timestamp) }
    }

    override suspend fun updatePhotoReconcileCursor(capturedAt: Long, memoryId: String) {
        _settings.update {
            it.copy(
                lastPhotoReconciledCapturedAt = capturedAt,
                lastPhotoReconciledMemoryId = memoryId
            )
        }
    }

    override suspend fun resetPhotoReconcileCursor() {
        _settings.update {
            it.copy(
                lastPhotoReconciledCapturedAt = Long.MAX_VALUE,
                lastPhotoReconciledMemoryId = ""
            )
        }
    }

    override suspend fun updateRecordingReconcileCursor(capturedAt: Long, memoryId: String) {
        _settings.update {
            it.copy(
                lastRecordingReconciledCapturedAt = capturedAt,
                lastRecordingReconciledMemoryId = memoryId
            )
        }
    }

    override suspend fun resetRecordingReconcileCursor() {
        _settings.update {
            it.copy(
                lastRecordingReconciledCapturedAt = Long.MAX_VALUE,
                lastRecordingReconciledMemoryId = ""
            )
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
        _settings.update {
            it.copy(
                sttModelId = modelId,
                sttModelVersion = modelVersion,
                sttModelSha256 = modelSha256,
                verifiedModelSha256 = modelSha256,
                verifiedTokensSha256 = tokensSha256,
                sttModelFileSize = fileSize,
                sttModelLastModified = lastModified,
                sttModelInstalledAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun clearSttModelMetadata() {
        _settings.update {
            it.copy(
                sttModelId = null,
                sttModelVersion = null,
                sttModelSha256 = null,
                verifiedModelSha256 = null,
                verifiedTokensSha256 = null,
                sttModelFileSize = 0L,
                sttModelLastModified = 0L,
                sttModelInstalledAt = 0L
            )
        }
    }

    override suspend fun updateDriveAuthStatus(status: com.cayana.backup.drive.DriveAuthStatus) {
        _settings.update {
            it.copy(
                driveAuthStatus = status,
                isDriveBackupConnected = (status == com.cayana.backup.drive.DriveAuthStatus.CONNECTED)
            )
        }
    }

    override suspend fun updateAutoBackupEnabled(enabled: Boolean) {
        _settings.update { it.copy(autoBackupEnabled = enabled) }
    }

    override suspend fun updateLastBackupTimestamp(timestamp: Long) {
        _settings.update { it.copy(lastBackupTimestamp = timestamp) }
    }

    override suspend fun updateHasRecoveryKey(hasKey: Boolean) {
        _settings.update { it.copy(hasRecoveryKey = hasKey) }
    }

    override suspend fun applyPortableSettings(portable: com.cayana.backup.snapshot.PortableUserSettings) {
        _settings.update { current ->
            val parsedSources = portable.enabledSources.mapNotNull { name ->
                try {
                    SourceType.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.toSet()
            current.copy(
                onboardingCompleted = portable.onboardingCompleted,
                enabledSources = parsedSources,
                notificationsEnabled = portable.notificationsEnabled,
                privateLoggingEnforced = portable.privateLoggingEnforced,
                isLocalFirstOnly = portable.isLocalFirstOnly
            )
        }
    }
}
