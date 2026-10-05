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
    private val recordingCoordinator: RecordingProcessingCoordinator? = null
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
}
