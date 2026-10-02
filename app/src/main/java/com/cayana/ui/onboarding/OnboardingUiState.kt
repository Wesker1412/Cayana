package com.cayana.ui.onboarding

import com.cayana.calendar.CalendarTarget
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType

enum class OnboardingStep {
    WELCOME,
    SOURCES,
    CALENDAR,
    BACKUP,
    FINISH
}

data class SourceItemUiState(
    val type: SourceType,
    val title: String,
    val description: String,
    val isEnabled: Boolean,
    val status: SourceStatus
)

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val sources: List<SourceItemUiState> = emptyList(),
    val calendars: List<CalendarTarget> = emptyList(),
    val selectedCalendarId: String? = null,
    val selectedCalendarName: String? = null,
    val hasCalendarPermission: Boolean = false,
    val isLoadingCalendars: Boolean = false,
    val isOnboardingCompleted: Boolean = false
)
