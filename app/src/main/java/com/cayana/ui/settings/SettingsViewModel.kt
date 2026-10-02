package com.cayana.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cayana.calendar.CalendarProviderHelper
import com.cayana.calendar.CalendarTarget
import com.cayana.core.permission.PermissionChecker
import com.cayana.source.SourceType
import com.cayana.ui.onboarding.SourceItemUiState
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val calendarProviderHelper: CalendarProviderHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val deniedSources = mutableSetOf<SourceType>()
    private var lastUserSettings: UserSettings = UserSettings()

    init {
        viewModelScope.launch {
            settingsRepository.getSettings().collect { userSettings ->
                lastUserSettings = userSettings
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
                        sourceItems = sourceItems
                    )
                }
            }
        }
        checkCalendarPermission()
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
        viewModelScope.launch {
            settingsRepository.updateSourceEnabled(sourceType, enabled)
        }
    }

    fun onPermissionResult(sourceType: SourceType, isGranted: Boolean) {
        if (isGranted) {
            deniedSources.remove(sourceType)
        } else {
            deniedSources.add(sourceType)
        }
        refreshSources(lastUserSettings)
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
}
