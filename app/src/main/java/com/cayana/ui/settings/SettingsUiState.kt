package com.cayana.ui.settings

import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.UserSettings

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val appVersion: String = "0.1.0 (Stage 0)",
    val isPrivacyStrict: Boolean = true
)
