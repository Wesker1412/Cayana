package com.cayana.ui.settings

import com.cayana.calendar.CalendarTarget
import com.cayana.ui.onboarding.SourceItemUiState
import com.cayana.ui.settings.repository.UserSettings

sealed interface ModelInstallUiState {
    data object Idle : ModelInstallUiState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percentage: Int) : ModelInstallUiState
    data object Verifying : ModelInstallUiState
    data object Installing : ModelInstallUiState
    data object Completed : ModelInstallUiState
    data class Failed(val message: String) : ModelInstallUiState
}

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val sourceItems: List<SourceItemUiState> = emptyList(),
    val availableCalendars: List<CalendarTarget> = emptyList(),
    val isLoadingCalendars: Boolean = false,
    val hasCalendarPermission: Boolean = false,
    val appVersion: String = "0.1.0 (Stage 1)",
    val isPrivacyStrict: Boolean = true,
    val isSttModelInstalled: Boolean = false,
    val showSttModelDialog: Boolean = false,
    val sttModelInstallState: ModelInstallUiState = ModelInstallUiState.Idle
)
