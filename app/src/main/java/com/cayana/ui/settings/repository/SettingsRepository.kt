package com.cayana.ui.settings.repository

import com.cayana.source.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UserSettings(
    val enabledSources: Set<SourceType> = setOf(SourceType.SCREENSHOT, SourceType.PHOTO),
    val autoCalendarEnabled: Boolean = false,
    val privateLoggingEnforced: Boolean = true,
    val isLocalFirstOnly: Boolean = true
)

interface SettingsRepository {
    fun getSettings(): Flow<UserSettings>
    suspend fun updateSourceEnabled(sourceType: SourceType, enabled: Boolean)
    suspend fun updateAutoCalendar(enabled: Boolean)
}

class LocalSettingsRepository : SettingsRepository {
    private val _settings = MutableStateFlow(UserSettings())

    override fun getSettings(): Flow<UserSettings> = _settings.asStateFlow()

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

    override suspend fun updateAutoCalendar(enabled: Boolean) {
        _settings.update { it.copy(autoCalendarEnabled = enabled) }
    }
}
