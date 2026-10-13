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

enum class NotionTransmissionPolicy {
    OFF,
    ASK_EACH_TIME,
    AUTO_FOR_SELECTED_TYPES
}

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
    val hasRecoveryKey: Boolean = false,
    val isGoogleTasksConnected: Boolean = false,
    val googleTasksAuthStatus: com.cayana.actions.tasks.auth.TasksAuthStatus = com.cayana.actions.tasks.auth.TasksAuthStatus.DISCONNECTED,
    val autoTasksEnabled: Boolean = false,
    val selectedTaskListId: String? = null,
    val selectedTaskListTitle: String? = null,
    val autoTasksEligibilityEpoch: Long = 0L,
    val isNotionConnected: Boolean = false,
    val notionAuthStatus: com.cayana.actions.notion.auth.NotionAuthStatus = com.cayana.actions.notion.auth.NotionAuthStatus.DISCONNECTED,
    val autoNotionEnabled: Boolean = false,
    val notionTransmissionPolicy: NotionTransmissionPolicy = NotionTransmissionPolicy.OFF,
    val autoNotionSources: Set<SourceType> = setOf(SourceType.SCREENSHOT),
    val autoNotionEligibilityEpoch: Long = 0L,
    val selectedNotionTargetId: String? = null,
    val selectedNotionTargetTitle: String? = null,
    val selectedNotionTargetType: String? = null,
    val notionWorkspaceName: String? = null,
    val notionWorkspaceIcon: String? = null
) {
    val googleTasksEligibilityEpoch: Long get() = autoTasksEligibilityEpoch
}

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
    suspend fun updateGoogleTasksAuthStatus(status: com.cayana.actions.tasks.auth.TasksAuthStatus)
    suspend fun updateAutoTasksEligibilityEpoch(epoch: Long)
    suspend fun updateGoogleTasksEligibilityEpoch(epoch: Long)
    suspend fun updateAutoTasksEnabled(enabled: Boolean)
    suspend fun updateSelectedTaskList(listId: String?, listTitle: String?)
    suspend fun updateNotionAuthStatus(status: com.cayana.actions.notion.auth.NotionAuthStatus)
    suspend fun updateAutoNotionEnabled(enabled: Boolean)
    suspend fun updateNotionTransmissionPolicy(policy: NotionTransmissionPolicy)
    suspend fun updateAutoNotionSources(sources: Set<SourceType>)
    suspend fun updateAutoNotionEligibilityEpoch(epoch: Long)
    suspend fun updateSelectedNotionTarget(targetId: String?, targetTitle: String?, targetType: String? = null)
    suspend fun updateNotionWorkspaceInfo(workspaceName: String?, workspaceIcon: String?)
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

    override suspend fun updateGoogleTasksAuthStatus(status: com.cayana.actions.tasks.auth.TasksAuthStatus) {
        _settings.update {
            val isConnected = (status == com.cayana.actions.tasks.auth.TasksAuthStatus.CONNECTED)
            val autoEnabled = if (!isConnected) false else it.autoTasksEnabled
            val epoch = if (!isConnected) 0L else it.autoTasksEligibilityEpoch
            it.copy(
                googleTasksAuthStatus = status,
                isGoogleTasksConnected = isConnected,
                autoTasksEnabled = autoEnabled,
                autoTasksEligibilityEpoch = epoch
            )
        }
    }

    override suspend fun updateAutoTasksEnabled(enabled: Boolean) {
        _settings.update {
            val epoch = if (enabled) {
                System.currentTimeMillis()
            } else {
                0L
            }
            it.copy(
                autoTasksEnabled = enabled,
                autoTasksEligibilityEpoch = epoch
            )
        }
    }

    override suspend fun updateAutoTasksEligibilityEpoch(epoch: Long) {
        _settings.update { it.copy(autoTasksEligibilityEpoch = epoch) }
    }

    override suspend fun updateGoogleTasksEligibilityEpoch(epoch: Long) {
        updateAutoTasksEligibilityEpoch(epoch)
    }

    override suspend fun updateSelectedTaskList(listId: String?, listTitle: String?) {
        _settings.update { it.copy(selectedTaskListId = listId, selectedTaskListTitle = listTitle) }
    }

    override suspend fun updateNotionAuthStatus(status: com.cayana.actions.notion.auth.NotionAuthStatus) {
        _settings.update {
            val isConnected = (status == com.cayana.actions.notion.auth.NotionAuthStatus.CONNECTED)
            val policy = if (!isConnected) NotionTransmissionPolicy.OFF else it.notionTransmissionPolicy
            val autoEnabled = if (!isConnected) false else it.autoNotionEnabled
            val epoch = if (!isConnected || !autoEnabled) 0L else it.autoNotionEligibilityEpoch
            it.copy(
                notionAuthStatus = status,
                isNotionConnected = isConnected,
                autoNotionEnabled = autoEnabled,
                notionTransmissionPolicy = policy,
                autoNotionEligibilityEpoch = epoch
            )
        }
    }

    override suspend fun updateAutoNotionEnabled(enabled: Boolean) {
        _settings.update {
            val policy = if (enabled) NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES else NotionTransmissionPolicy.OFF
            val newEpoch = if (enabled) {
                System.currentTimeMillis()
            } else {
                0L
            }
            it.copy(
                autoNotionEnabled = enabled,
                notionTransmissionPolicy = policy,
                autoNotionEligibilityEpoch = newEpoch
            )
        }
    }

    override suspend fun updateNotionTransmissionPolicy(policy: NotionTransmissionPolicy) {
        _settings.update {
            val autoEnabled = (policy == NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
            val newEpoch = if (autoEnabled) {
                System.currentTimeMillis()
            } else {
                0L
            }
            it.copy(
                notionTransmissionPolicy = policy,
                autoNotionEnabled = autoEnabled,
                autoNotionEligibilityEpoch = newEpoch
            )
        }
    }

    override suspend fun updateAutoNotionSources(sources: Set<SourceType>) {
        _settings.update { it.copy(autoNotionSources = sources) }
    }

    override suspend fun updateAutoNotionEligibilityEpoch(epoch: Long) {
        _settings.update { it.copy(autoNotionEligibilityEpoch = epoch) }
    }

    override suspend fun updateSelectedNotionTarget(targetId: String?, targetTitle: String?, targetType: String?) {
        _settings.update {
            it.copy(
                selectedNotionTargetId = targetId,
                selectedNotionTargetTitle = targetTitle,
                selectedNotionTargetType = targetType
            )
        }
    }

    override suspend fun updateNotionWorkspaceInfo(workspaceName: String?, workspaceIcon: String?) {
        _settings.update {
            it.copy(
                notionWorkspaceName = workspaceName,
                notionWorkspaceIcon = workspaceIcon
            )
        }
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
