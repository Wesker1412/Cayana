package com.cayana.core.di

import com.cayana.ui.home.HomeViewModel
import com.cayana.ui.onboarding.OnboardingViewModel
import com.cayana.ui.settings.SettingsViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel {
        HomeViewModel(
            memoryRepository = get(),
            searchEngine = getOrNull(),
            sourceValidator = getOrNull()
        )
    }

    viewModel {
        SettingsViewModel(
            settingsRepository = get(),
            permissionChecker = get(),
            calendarProviderHelper = get(),
            screenshotWatcher = getOrNull(),
            modelInstaller = getOrNull(),
            sttEngine = getOrNull(),
            recordingCoordinator = getOrNull()
        )
    }

    viewModel {
        OnboardingViewModel(
            settingsRepository = get(),
            permissionChecker = get(),
            calendarProviderHelper = get(),
            screenshotWatcher = getOrNull()
        )
    }
}
