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
    private val photoWatcher: com.cayana.source.photo.PhotoSourceWatcher by inject()
    private val recordingWatcher: com.cayana.source.recording.RecordingSourceWatcher by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val permissionChecker: PermissionChecker by inject()
    private val calendarCoordinator: com.cayana.calendar.CalendarProcessingCoordinator by inject()
    private val recordingCoordinator: com.cayana.source.recording.RecordingProcessingCoordinator by inject()
    private val photoCoordinator: com.cayana.source.photo.PhotoProcessingCoordinator by inject()

    override fun onCreate() {
        super.onCreate()
        initKoin()
        initWatchers()
        reconcileInFlightTasks()
    }

    private fun reconcileInFlightTasks() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                calendarCoordinator.reconcilePendingCreatingActions()
            } catch (_: Exception) {}
            try {
                recordingCoordinator.reconcileInFlightRecordings()
            } catch (_: Exception) {}
            try {
                photoCoordinator.reconcileDeletedPhotos()
            } catch (_: Exception) {}
            try {
                recordingCoordinator.reconcileDeletedRecordings()
            } catch (_: Exception) {}
        }
    }

    private fun initWatchers() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                settingsRepository.getSettings().collect { settings ->
                    // 1. Screenshot Watcher
                    val isScreenshotEnabled = settings.enabledSources.contains(SourceType.SCREENSHOT)
                    val screenshotStatus = permissionChecker.getSourceStatus(
                        sourceType = SourceType.SCREENSHOT,
                        isEnabled = isScreenshotEnabled,
                        isDenied = false,
                        customUri = null
                    )
                    if (screenshotStatus == SourceStatus.ENABLED_AND_AUTHORIZED) {
                        screenshotWatcher.startWatching()
                    } else {
                        screenshotWatcher.stopWatching()
                    }

                    // 2. Photo Watcher
                    val isPhotoEnabled = settings.enabledSources.contains(SourceType.PHOTO)
                    val photoStatus = permissionChecker.getSourceStatus(
                        sourceType = SourceType.PHOTO,
                        isEnabled = isPhotoEnabled,
                        isDenied = false,
                        customUri = null
                    )
                    if (photoStatus == SourceStatus.ENABLED_AND_AUTHORIZED && !permissionChecker.hasLimitedAccess(SourceType.PHOTO)) {
                        photoWatcher.startWatching()
                    } else {
                        photoWatcher.stopWatching()
                    }

                    // 3. Recording Watcher
                    val isRecordingEnabled = settings.enabledSources.contains(SourceType.RECORDING)
                    val recordingStatus = permissionChecker.getSourceStatus(
                        sourceType = SourceType.RECORDING,
                        isEnabled = isRecordingEnabled,
                        isDenied = false,
                        customUri = null
                    )
                    if (recordingStatus == SourceStatus.ENABLED_AND_AUTHORIZED) {
                        recordingWatcher.startWatching()
                    } else {
                        recordingWatcher.stopWatching()
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
