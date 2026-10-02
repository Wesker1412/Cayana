package com.cayana.ui.settings

import com.cayana.calendar.CalendarTarget
import com.cayana.ui.onboarding.SourceItemUiState
import com.cayana.ui.settings.repository.UserSettings

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val sourceItems: List<SourceItemUiState> = emptyList(),
    val availableCalendars: List<CalendarTarget> = emptyList(),
    val isLoadingCalendars: Boolean = false,
    val hasCalendarPermission: Boolean = false,
    val appVersion: String = "0.1.0 (Stage 1)",
    val isPrivacyStrict: Boolean = true
)
