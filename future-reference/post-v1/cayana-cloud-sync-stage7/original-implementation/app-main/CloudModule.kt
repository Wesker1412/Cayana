package com.cayana.core.di

import com.cayana.cloud.auth.AndroidKeystoreRefreshTokenStorage
import com.cayana.cloud.auth.CloudAuthManager
import com.cayana.cloud.auth.RefreshTokenStorage
import com.cayana.cloud.auth.SupabaseCloudAuthManager
import com.cayana.cloud.client.CayanaCloudClient
import com.cayana.cloud.client.SupabaseCayanaCloudClient
import com.cayana.cloud.config.CloudConfig
import com.cayana.cloud.sync.CloudSyncManager
import com.cayana.cloud.sync.DefaultCloudSyncManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val cloudModule = module {
    single<SupabaseClient> {
        val url = if (CloudConfig.isConfigured()) CloudConfig.supabaseUrl else "https://unconfigured.cayana.local"
        val key = if (CloudConfig.isConfigured()) CloudConfig.supabasePublishableKey else "unconfigured_key"
        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Auth) {
                sessionManager = io.github.jan.supabase.auth.MemorySessionManager()
                codeVerifierCache = io.github.jan.supabase.auth.MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
        }
    }

    single<RefreshTokenStorage> {
        AndroidKeystoreRefreshTokenStorage(androidContext())
    }

    single<CloudAuthManager> {
        SupabaseCloudAuthManager(
            supabaseClient = get(),
            refreshTokenStorage = get()
        )
    }

    single<CayanaCloudClient> {
        SupabaseCayanaCloudClient(
            supabaseClient = get(),
            authManager = get()
        )
    }

    single<CloudSyncManager> {
        DefaultCloudSyncManager(
            cloudSyncStateDao = get(),
            cloudMemorySyncMetadataDao = get(),
            cloudSyncOutboxDao = get(),
            memoryDao = get(),
            memoryRepository = get(),
            cloudClient = get(),
            cloudAuthManager = get(),
            recoveryKeyStorage = get(),
            database = get()
        )
    }
}
