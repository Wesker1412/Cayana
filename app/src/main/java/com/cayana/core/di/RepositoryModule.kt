package com.cayana.core.di

import com.cayana.memory.repository.MemoryRepository
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.search.DefaultMemorySearchEngine
import com.cayana.search.MemorySearchEngine
import com.cayana.ui.settings.repository.DataStoreSettingsRepository
import com.cayana.ui.settings.repository.SettingsRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<MemoryRepository> {
        RoomMemoryRepository(
            memoryDao = get(),
            dispatchers = get()
        )
    }

    single<SettingsRepository> {
        DataStoreSettingsRepository(dataStore = get())
    }

    single<MemorySearchEngine> {
        DefaultMemorySearchEngine(
            memoryRepository = get()
        )
    }
}
