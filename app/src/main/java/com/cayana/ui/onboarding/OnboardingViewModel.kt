package com.cayana.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cayana.calendar.CalendarProviderHelper
import com.cayana.calendar.CalendarTarget
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val settingsRepository: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val calendarProviderHelper: CalendarProviderHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    // Tracks which sources had permissions explicitly denied during onboarding
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
                        sources = sourceItems,
                        selectedCalendarId = userSettings.selectedCalendarId,
                        selectedCalendarName = userSettings.selectedCalendarName,
                        isOnboardingCompleted = userSettings.onboardingCompleted
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

    fun nextStep() {
        val next = when (_uiState.value.currentStep) {
            OnboardingStep.WELCOME -> OnboardingStep.SOURCES
            OnboardingStep.SOURCES -> {
                checkCalendarPermission()
                OnboardingStep.CALENDAR
            }
            OnboardingStep.CALENDAR -> OnboardingStep.BACKUP
            OnboardingStep.BACKUP -> OnboardingStep.FINISH
            OnboardingStep.FINISH -> OnboardingStep.FINISH
        }
        _uiState.update { it.copy(currentStep = next) }
    }

    fun previousStep() {
        val prev = when (_uiState.value.currentStep) {
            OnboardingStep.WELCOME -> OnboardingStep.WELCOME
            OnboardingStep.SOURCES -> OnboardingStep.WELCOME
            OnboardingStep.CALENDAR -> OnboardingStep.SOURCES
            OnboardingStep.BACKUP -> OnboardingStep.CALENDAR
            OnboardingStep.FINISH -> OnboardingStep.BACKUP
        }
        _uiState.update { it.copy(currentStep = prev) }
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
            val updatedSources = current.sources.map { item ->
                val isDenied = deniedSources.contains(item.type)
                val uri = if (item.type == SourceType.DOWNLOAD) userSettings.downloadsDirectoryUri else null
                val status = permissionChecker.getSourceStatus(item.type, item.isEnabled, isDenied, uri)
                item.copy(status = status)
            }
            current.copy(sources = updatedSources)
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
                    calendars = calendars,
                    isLoadingCalendars = false
                )
            }
            // Auto-select primary or first if none selected
            if (_uiState.value.selectedCalendarId == null && calendars.isNotEmpty()) {
                val defaultTarget = calendars.find { it.isPrimary } ?: calendars.first()
                selectCalendar(defaultTarget)
            }
        }
    }

    fun selectCalendar(target: CalendarTarget) {
        _uiState.update {
            it.copy(
                selectedCalendarId = target.id,
                selectedCalendarName = target.displayName
            )
        }
        viewModelScope.launch {
            settingsRepository.updateSelectedCalendar(target.id, target.displayName)
        }
    }

    fun skipCalendar() {
        _uiState.update {
            it.copy(
                selectedCalendarId = null,
                selectedCalendarName = null
            )
        }
        viewModelScope.launch {
            settingsRepository.updateSelectedCalendar(null, null)
        }
        nextStep()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
            _uiState.update { it.copy(isOnboardingCompleted = true) }
        }
    }
}
