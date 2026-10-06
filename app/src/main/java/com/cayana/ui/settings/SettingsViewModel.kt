package com.cayana.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cayana.calendar.CalendarProviderHelper
import com.cayana.calendar.CalendarTarget
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType
import com.cayana.source.screenshot.ScreenshotSourceWatcher
import com.cayana.ui.onboarding.SourceItemUiState
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.cayana.processing.SpeechToTextEngine
import com.cayana.processing.stt.SherpaModelInstaller
import com.cayana.source.recording.RecordingProcessingCoordinator

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val calendarProviderHelper: CalendarProviderHelper,
    private val screenshotWatcher: ScreenshotSourceWatcher? = null,
    private val modelInstaller: SherpaModelInstaller? = null,
    private val sttEngine: SpeechToTextEngine? = null,
    private val recordingCoordinator: RecordingProcessingCoordinator? = null,
    private val backupManager: com.cayana.backup.manager.BackupManager? = null,
    private val driveAuthManager: com.cayana.backup.drive.DriveAuthorizationManager? = null,
    private val recoveryKeyStorage: com.cayana.backup.crypto.RecoveryKeyStorage? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val deniedSources = mutableSetOf<SourceType>()
    private var lastUserSettings: UserSettings = UserSettings()

    init {
        viewModelScope.launch {
            settingsRepository.getSettings().collect { userSettings ->
                lastUserSettings = userSettings
                val isInstalled = userSettings.verifiedModelSha256 != null || (sttEngine?.isModelAvailable == true)
                val sourceItems = listOf(
                    createSourceItem(
                        SourceType.SCREENSHOT,
                        "Screenshots",
                        "Automatically index captured screenshots",
                        userSettings.enabledSources.contains(SourceType.SCREENSHOT),
                        null
                    ),
                    createSourceItem(
                        SourceType.PHOTO,
                        "Camera & Photos",
                        "Index photos with readable text or places",
                        userSettings.enabledSources.contains(SourceType.PHOTO),
                        null
                    ),
                    createSourceItem(
                        SourceType.RECORDING,
                        "Voice Recordings",
                        "Transcribe and index audio notes locally",
                        userSettings.enabledSources.contains(SourceType.RECORDING),
                        null
                    ),
                    createSourceItem(
                        SourceType.DOWNLOAD,
                        "Downloads",
                        "Index documents and receipts from downloads",
                        userSettings.enabledSources.contains(SourceType.DOWNLOAD),
                        userSettings.downloadsDirectoryUri
                    )
                )

                _uiState.update { current ->
                    current.copy(
                        settings = userSettings,
                        sourceItems = sourceItems,
                        isSttModelInstalled = isInstalled
                    )
                }
            }
        }
        checkCalendarPermission()
        checkModelStatus()
    }

    private fun createSourceItem(
        type: SourceType,
        title: String,
        description: String,
        isEnabled: Boolean,
        customUri: String? = null
    ): SourceItemUiState {
        val isDenied = deniedSources.contains(type)
        val status = permissionChecker.getSourceStatus(type, isEnabled, isDenied, customUri)
        return SourceItemUiState(
            type = type,
            title = title,
            description = description,
            isEnabled = isEnabled,
            status = status
        )
    }

    fun toggleSource(sourceType: SourceType, enabled: Boolean) {
        if (!enabled) {
            deniedSources.remove(sourceType)
        }
        if (sourceType == SourceType.RECORDING && enabled && !_uiState.value.isSttModelInstalled) {
            _uiState.update { it.copy(showSttModelDialog = true) }
        }
        viewModelScope.launch {
            settingsRepository.updateSourceEnabled(sourceType, enabled)
            when (sourceType) {
                SourceType.SCREENSHOT -> {
                    if (!enabled) {
                        settingsRepository.updateScreenshotWatcherStatus(com.cayana.ui.settings.repository.SourceWatcherStatus.DISABLED)
                    }
                    syncScreenshotWatcher()
                }
                SourceType.PHOTO -> {
                    if (!enabled) {
                        settingsRepository.updatePhotoWatcherStatus(com.cayana.ui.settings.repository.SourceWatcherStatus.DISABLED)
                    }
                }
                SourceType.RECORDING -> {
                    if (!enabled) {
                        settingsRepository.updateRecordingWatcherStatus(com.cayana.ui.settings.repository.SourceWatcherStatus.DISABLED)
                    }
                }
                else -> {}
            }
        }
    }

    fun onPermissionResult(sourceType: SourceType, isGranted: Boolean) {
        if (isGranted) {
            deniedSources.remove(sourceType)
        } else {
            deniedSources.add(sourceType)
        }
        refreshSources(lastUserSettings)
        if (sourceType == SourceType.SCREENSHOT) {
            syncScreenshotWatcher()
        }
    }

    fun onDownloadsUriSelected(uriString: String?) {
        if (uriString != null) {
            deniedSources.remove(SourceType.DOWNLOAD)
            viewModelScope.launch {
                settingsRepository.updateDownloadsDirectoryUri(uriString)
                settingsRepository.updateSourceEnabled(SourceType.DOWNLOAD, true)
            }
        } else {
            deniedSources.add(SourceType.DOWNLOAD)
            refreshSources(lastUserSettings)
        }
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            val userSettings = settingsRepository.getSettings().first()
            lastUserSettings = userSettings
            // Clear denied status if now granted externally (e.g. from Android Settings)
            deniedSources.removeAll { type ->
                val uri = if (type == SourceType.DOWNLOAD) userSettings.downloadsDirectoryUri else null
                permissionChecker.isSourceAuthorized(type, uri) || permissionChecker.hasLimitedAccess(type)
            }
            refreshSources(userSettings)
            checkCalendarPermission()
            syncScreenshotWatcher(userSettings)
        }
    }

    private fun syncScreenshotWatcher(userSettings: UserSettings = lastUserSettings) {
        val isEnabled = userSettings.enabledSources.contains(SourceType.SCREENSHOT)
        val isDenied = deniedSources.contains(SourceType.SCREENSHOT)
        val status = permissionChecker.getSourceStatus(SourceType.SCREENSHOT, isEnabled, isDenied, null)
        if (status == SourceStatus.ENABLED_AND_AUTHORIZED) {
            screenshotWatcher?.startWatching()
        } else {
            screenshotWatcher?.stopWatching()
        }
    }

    private fun refreshSources(userSettings: UserSettings = lastUserSettings) {
        _uiState.update { current ->
            val updatedSources = current.sourceItems.map { item ->
                val isDenied = deniedSources.contains(item.type)
                val uri = if (item.type == SourceType.DOWNLOAD) userSettings.downloadsDirectoryUri else null
                val status = permissionChecker.getSourceStatus(item.type, item.isEnabled, isDenied, uri)
                item.copy(status = status)
            }
            current.copy(sourceItems = updatedSources)
        }
    }

    fun checkCalendarPermission() {
        val hasPermission = calendarProviderHelper.hasCalendarPermission()
        _uiState.update { it.copy(hasCalendarPermission = hasPermission) }
        if (hasPermission) {
            loadCalendars()
        }
    }

    fun loadCalendars() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCalendars = true) }
            val calendars = calendarProviderHelper.getWritableCalendars()
            _uiState.update {
                it.copy(
                    availableCalendars = calendars,
                    isLoadingCalendars = false
                )
            }
        }
    }

    fun selectCalendar(target: CalendarTarget) {
        viewModelScope.launch {
            settingsRepository.updateSelectedCalendar(target.id, target.displayName)
        }
    }

    fun clearCalendar() {
        viewModelScope.launch {
            settingsRepository.updateSelectedCalendar(null, null)
        }
    }

    fun toggleAutoCalendar(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoCalendar(enabled)
        }
    }

    fun showSttModelDialog() {
        _uiState.update { it.copy(showSttModelDialog = true) }
    }

    fun dismissSttModelDialog() {
        _uiState.update { it.copy(showSttModelDialog = false, sttModelInstallState = ModelInstallUiState.Idle) }
    }

    fun checkModelStatus() {
        val available = sttEngine?.isModelAvailable ?: false
        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            val installed = available || settings.verifiedModelSha256 != null
            _uiState.update { it.copy(isSttModelInstalled = installed) }
        }
    }

    fun startModelDownload(customUrl: String? = null) {
        val installer = modelInstaller ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(sttModelInstallState = ModelInstallUiState.Downloading(0L, 0L, 0)) }
            try {
                val success = if (customUrl != null) {
                    installer.downloadAndInstall(customUrl) { progress ->
                        updateInstallProgress(progress)
                    }
                } else {
                    installer.downloadAndInstall { progress ->
                        updateInstallProgress(progress)
                    }
                }
                if (success) {
                    checkModelStatus()
                    _uiState.update {
                        it.copy(
                            isSttModelInstalled = true,
                            sttModelInstallState = ModelInstallUiState.Completed
                        )
                    }
                    recordingCoordinator?.reconcileInFlightRecordings()
                } else {
                    _uiState.update {
                        it.copy(sttModelInstallState = ModelInstallUiState.Failed("Download failed"))
                    }
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(sttModelInstallState = ModelInstallUiState.Failed(e.message ?: "Download failed"))
                }
            }
        }
    }

    private fun updateInstallProgress(progress: SherpaModelInstaller.InstallProgress) {
        when (progress) {
            is SherpaModelInstaller.InstallProgress.Downloading -> {
                _uiState.update {
                    it.copy(
                        sttModelInstallState = ModelInstallUiState.Downloading(
                            progress.bytesDownloaded,
                            progress.totalBytes,
                            progress.percentage
                        )
                    )
                }
            }
            is SherpaModelInstaller.InstallProgress.Verifying -> {
                _uiState.update { it.copy(sttModelInstallState = ModelInstallUiState.Verifying) }
            }
            is SherpaModelInstaller.InstallProgress.Installing -> {
                _uiState.update { it.copy(sttModelInstallState = ModelInstallUiState.Installing) }
            }
            is SherpaModelInstaller.InstallProgress.Completed -> {
                _uiState.update {
                    it.copy(
                        isSttModelInstalled = true,
                        sttModelInstallState = ModelInstallUiState.Completed
                    )
                }
            }
            is SherpaModelInstaller.InstallProgress.Failed -> {
                _uiState.update {
                    it.copy(
                        sttModelInstallState = ModelInstallUiState.Failed(progress.error.message ?: "Installation failed")
                    )
                }
            }
        }
    }

    fun startConnectDrive(launcher: (android.content.IntentSender) -> Unit) {
        viewModelScope.launch {
            if (recoveryKeyStorage?.hasRecoveryKey() != true) {
                val newKey = com.cayana.backup.crypto.RecoveryKeyManager.generateRootKey()
                val formatted = com.cayana.backup.crypto.RecoveryKeyManager.formatKey(newKey)
                _uiState.update { it.copy(showRecoveryKeyDialog = true, generatedRecoveryKey = formatted) }
            }

            val authManager = driveAuthManager ?: return@launch
            val res = authManager.getAuthorizationIntentSender()
            if (res is com.cayana.core.common.Result.Success) {
                val sender = res.data
                if (sender != null) {
                    launcher(sender)
                } else {
                    settingsRepository.updateDriveAuthStatus(com.cayana.backup.drive.DriveAuthStatus.CONNECTED)
                }
            }
        }
    }

    fun confirmRecoveryKey(confirmationInput: String? = null): Boolean {
        val keyStr = _uiState.value.generatedRecoveryKey
        if (keyStr.isNullOrBlank()) {
            _uiState.update { it.copy(showRecoveryKeyDialog = false) }
            return false
        }

        val chunks = keyStr.split("-")
        if (confirmationInput != null) {
            if (chunks.size < 2) {
                _uiState.update { it.copy(recoveryKeyConfirmationError = "無效的金鑰格式") }
                return false
            }
            val expected = chunks.takeLast(2).joinToString("").uppercase()
            val cleanInput = confirmationInput.trim().replace("-", "").replace(" ", "").uppercase()
            if (cleanInput != expected) {
                _uiState.update { it.copy(recoveryKeyConfirmationError = "輸入的末兩組金鑰不符，請重新確認。") }
                return false
            }
        }

        val keyBytes = runCatching { com.cayana.backup.crypto.RecoveryKeyManager.parseKey(keyStr) }.getOrNull()
        if (keyBytes != null) {
            recoveryKeyStorage?.saveRecoveryKey(keyBytes)
            viewModelScope.launch {
                settingsRepository.updateHasRecoveryKey(true)
            }
            _uiState.update {
                it.copy(
                    showRecoveryKeyDialog = false,
                    generatedRecoveryKey = null,
                    recoveryKeyConfirmationError = null
                )
            }
            return true
        } else {
            _uiState.update { it.copy(recoveryKeyConfirmationError = "金鑰校驗碼錯誤") }
            return false
        }
    }

    fun dismissRecoveryKeyDialog() {
        _uiState.update { it.copy(showRecoveryKeyDialog = false, recoveryKeyConfirmationError = null) }
    }

    fun onDriveAuthorizationResult(success: Boolean, data: android.content.Intent? = null) {
        viewModelScope.launch {
            if (success && data != null && driveAuthManager != null) {
                val res = driveAuthManager.handleAuthorizationResult(data)
                if (res is com.cayana.core.common.Result.Success) {
                    settingsRepository.updateDriveAuthStatus(com.cayana.backup.drive.DriveAuthStatus.CONNECTED)
                } else {
                    settingsRepository.updateDriveAuthStatus(com.cayana.backup.drive.DriveAuthStatus.AUTH_REQUIRED)
                }
            } else if (success && driveAuthManager != null && data == null) {
                val tokenRes = driveAuthManager.getAccessToken()
                if (tokenRes is com.cayana.core.common.Result.Success) {
                    settingsRepository.updateDriveAuthStatus(com.cayana.backup.drive.DriveAuthStatus.CONNECTED)
                } else {
                    settingsRepository.updateDriveAuthStatus(com.cayana.backup.drive.DriveAuthStatus.AUTH_REQUIRED)
                }
            } else {
                settingsRepository.updateDriveAuthStatus(com.cayana.backup.drive.DriveAuthStatus.AUTH_REQUIRED)
            }
        }
    }

    fun disconnectDrive(context: android.content.Context? = null) {
        viewModelScope.launch {
            val authManager = driveAuthManager
            val res = authManager?.revokeAuthorization()
            if (res == null || res is com.cayana.core.common.Result.Success) {
                settingsRepository.updateDriveAuthStatus(com.cayana.backup.drive.DriveAuthStatus.DISCONNECTED)
                settingsRepository.updateAutoBackupEnabled(false)
                if (context != null) {
                    com.cayana.backup.worker.AutoBackupWorker.cancel(context)
                }
            } else {
                _uiState.update { it.copy(backupState = BackupUiState.Error("解除 Google 授權失敗，請確認網路連線。")) }
            }
        }
    }

    fun toggleAutoBackup(enabled: Boolean, context: android.content.Context) {
        if (enabled && !_uiState.value.settings.hasRecoveryKey) {
            _uiState.update { it.copy(backupState = BackupUiState.Error("啟用自動備份前必須先確認並保存復原金鑰。")) }
            return
        }
        viewModelScope.launch {
            settingsRepository.updateAutoBackupEnabled(enabled)
            if (enabled) {
                com.cayana.backup.worker.AutoBackupWorker.schedule(context)
            } else {
                com.cayana.backup.worker.AutoBackupWorker.cancel(context)
            }
        }
    }

    fun performManualBackup(): Job =
        viewModelScope.launch {
            val bManager = backupManager ?: return@launch
            _uiState.update { it.copy(backupState = BackupUiState.BackingUp) }
            when (val res = bManager.performBackup()) {
                is com.cayana.core.common.Result.Success -> {
                    _uiState.update { it.copy(backupState = BackupUiState.Success("備份成功！已上傳至 Google Drive。")) }
                }
                is com.cayana.core.common.Result.Error -> {
                    _uiState.update { it.copy(backupState = BackupUiState.Error(res.exception.message ?: "備份失敗。")) }
                }
                com.cayana.core.common.Result.Loading -> Unit
            }
        }

    fun openRestoreDialog() {
        viewModelScope.launch {
            _uiState.update { it.copy(showRestoreDialog = true, isFetchingBackups = true) }
            val bManager = backupManager ?: return@launch
            val res = bManager.listBackups()
            val backups = if (res is com.cayana.core.common.Result.Success) res.data else emptyList()
            _uiState.update { it.copy(availableBackups = backups, isFetchingBackups = false) }
        }
    }

    fun dismissRestoreDialog() {
        _uiState.update { it.copy(showRestoreDialog = false) }
    }

    fun performRestore(
        fileId: String,
        rawRecoveryKey: String,
        onConfirmReplaceLocal: suspend () -> Boolean = { true }
    ): Job =
        viewModelScope.launch {
            val bManager = backupManager ?: return@launch
            _uiState.update { it.copy(backupState = BackupUiState.Restoring) }
            when (val res = bManager.restoreBackup(fileId, rawRecoveryKey, onConfirmReplaceLocal)) {
                is com.cayana.core.common.Result.Success -> {
                    _uiState.update {
                        it.copy(
                            backupState = BackupUiState.Success("還原成功！已恢復 ${res.data.memoriesRestored} 筆記憶。"),
                            showRestoreDialog = false
                        )
                    }
                }
                is com.cayana.core.common.Result.Error -> {
                    _uiState.update { it.copy(backupState = BackupUiState.Error(res.exception.message ?: "還原失敗。")) }
                }
                com.cayana.core.common.Result.Loading -> Unit
            }
        }

    fun clearBackupState() {
        _uiState.update { it.copy(backupState = BackupUiState.Idle) }
    }
}
