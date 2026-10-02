package com.cayana.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = settingsRepository.getSettings()
        .map { userSettings ->
            SettingsUiState(settings = userSettings)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState()
        )

    fun toggleSource(sourceType: SourceType, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSourceEnabled(sourceType, enabled)
        }
    }

    fun toggleAutoCalendar(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoCalendar(enabled)
        }
    }
}
