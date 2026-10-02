package com.cayana

import android.app.Application
import com.cayana.core.di.appModule
import com.cayana.core.di.databaseModule
import com.cayana.core.di.repositoryModule
import com.cayana.core.di.viewModelModule
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType
import com.cayana.source.screenshot.ScreenshotSourceWatcher
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class CayanaApplication : Application() {

    private val screenshotWatcher: ScreenshotSourceWatcher by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val permissionChecker: PermissionChecker by inject()

    override fun onCreate() {
        super.onCreate()
        initKoin()
        initScreenshotWatcher()
    }

    private fun initScreenshotWatcher() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                settingsRepository.getSettings().collect { settings ->
                    val isEnabled = settings.enabledSources.contains(SourceType.SCREENSHOT)
                    val status = permissionChecker.getSourceStatus(
                        sourceType = SourceType.SCREENSHOT,
                        isEnabled = isEnabled,
                        isDenied = false,
                        customUri = null
                    )
                    if (status == SourceStatus.ENABLED_AND_AUTHORIZED) {
                        screenshotWatcher.startWatching()
                    } else {
                        screenshotWatcher.stopWatching()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun initKoin() {
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            startKoin {
                androidLogger()
                androidContext(this@CayanaApplication)
                modules(
                    appModule,
                    databaseModule,
                    repositoryModule,
                    viewModelModule
                )
            }
        }
    }
}
