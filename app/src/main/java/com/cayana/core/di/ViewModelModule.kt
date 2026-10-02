package com.cayana.core.di

import com.cayana.ui.home.HomeViewModel
import com.cayana.ui.settings.SettingsViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel {
        HomeViewModel(
            memoryRepository = get()
        )
    }

    viewModel {
        SettingsViewModel(
            settingsRepository = get()
        )
    }
}
