package com.cayana.source.screenshot

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceItem
import com.cayana.source.SourceType
import com.cayana.source.SourceWatcher
import com.cayana.ui.settings.repository.ScreenshotWatcherStatus
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Watcher implementation for MediaStore Screenshots.
 * Uses a ContentObserver for the process-alive fast path, and WorkManager ContentUriTrigger
 * as the persistent background wake path.
 */
class ScreenshotSourceWatcher(
    private val context: Context,
    private val permissionChecker: PermissionChecker,
    private val coordinator: ScreenshotProcessingCoordinator,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers = AppDispatchers()
) : SourceWatcher {

    override val sourceType: SourceType = SourceType.SCREENSHOT

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val _newItemsFlow = MutableSharedFlow<SourceItem>(extraBufferCapacity = 64)
    private val activationMutex = Mutex()
    @Volatile
    private var isWatching = false

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            CayanaLogger.d("ScreenshotWatcher", "ContentObserver onChange detected MediaStore update")
            scope.launch {
                val newMemories = coordinator.processPendingScreenshots()
                newMemories.forEach { memory ->
                    val sourceItem = SourceItem(
                        id = memory.id,
                        sourceType = SourceType.SCREENSHOT,
                        uri = memory.sourceUri ?: "",
                        capturedAt = memory.capturedAt,
                        metadata = memory.metadata
                    )
                    _newItemsFlow.tryEmit(sourceItem)
                }
            }
        }
    }

    override fun isAvailable(): Boolean {
        return permissionChecker.getSourceStatus(
            sourceType = SourceType.SCREENSHOT,
            isEnabled = true,
            isDenied = false,
            customUri = null
        ) == SourceStatus.ENABLED_AND_AUTHORIZED
    }

    override fun observeNewItems(): Flow<SourceItem> = _newItemsFlow.asSharedFlow()

    /**
     * Activates screenshot watching following a strict sequence:
     * 1. Baseline BEFORE Watcher: If uninitialized or disabled, establish baseline first so
     *    the high-water mark cursor is persisted before ContentObserver / WorkManager are registered.
     * 2. Register ContentObserver and WorkManager trigger.
     * 3. Catch up pending screenshots if this is a process restart with existing ACTIVE status.
     */
    suspend fun activateWatching() = withContext(dispatchers.io) {
        activationMutex.withLock {
            if (isWatching) return@withContext
            if (!isAvailable()) {
                CayanaLogger.d("ScreenshotWatcher", "Cannot start watching: permission not authorized.")
                return@withContext
            }

            val settings = settingsRepository.getSettings().first()
            val needsBaseline = settings.screenshotWatcherStatus == ScreenshotWatcherStatus.UNINITIALIZED ||
                    settings.screenshotWatcherStatus == ScreenshotWatcherStatus.DISABLED

            if (needsBaseline) {
                CayanaLogger.i("ScreenshotWatcher", "Establishing baseline BEFORE registering watcher (status=${settings.screenshotWatcherStatus}).")
                coordinator.establishBaseline(forceNew = true)
            }

            try {
                // 1. Process-alive fast path: ContentObserver
                context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    contentObserver
                )
                isWatching = true
                CayanaLogger.i("ScreenshotWatcher", "Started ContentObserver for MediaStore screenshots")

                // 2. Process-death persistent background path: WorkManager trigger
                ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)

                // 3. Catch-up on process restart with existing active status
                if (!needsBaseline) {
                    coordinator.processPendingScreenshots()
                }
            } catch (e: Exception) {
                CayanaLogger.w("ScreenshotWatcher", "Failed to register ContentObserver: ${e.javaClass.simpleName}")
            }
        }
    }

    fun startWatching() {
        scope.launch {
            activateWatching()
        }
    }

    @Synchronized
    fun stopWatching() {
        if (isWatching) {
            try {
                context.contentResolver.unregisterContentObserver(contentObserver)
            } catch (e: Exception) {
                CayanaLogger.w("ScreenshotWatcher", "Error unregistering ContentObserver: ${e.message}")
            }
            isWatching = false
        }
        ScreenshotIngestWorker.cancelTrigger(context)
        CayanaLogger.i("ScreenshotWatcher", "Stopped watching MediaStore screenshots and cancelled WorkManager trigger")
    }
}
