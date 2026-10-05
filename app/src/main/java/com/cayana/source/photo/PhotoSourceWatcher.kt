package com.cayana.source.photo

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
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
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
 * Watcher implementation for MediaStore Photos.
 * Uses ContentObserver as the alive fast path, and WorkManager ContentUriTrigger
 * as the persistent background wake path.
 */
class PhotoSourceWatcher(
    private val context: Context,
    private val permissionChecker: PermissionChecker,
    private val coordinator: PhotoProcessingCoordinator,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers = AppDispatchers()
) : SourceWatcher {

    override val sourceType: SourceType = SourceType.PHOTO

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val _newItemsFlow = MutableSharedFlow<SourceItem>(extraBufferCapacity = 64)
    private val activationMutex = Mutex()
    @Volatile
    private var isWatching = false

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            CayanaLogger.d("PhotoWatcher", "ContentObserver onChange detected Photo update")
            scope.launch {
                val newMemories = coordinator.processPendingPhotos()
                newMemories.forEach { memory ->
                    val sourceItem = SourceItem(
                        id = memory.id,
                        sourceType = SourceType.PHOTO,
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
        val status = permissionChecker.getSourceStatus(
            sourceType = SourceType.PHOTO,
            isEnabled = true,
            isDenied = false,
            customUri = null
        )
        return status == SourceStatus.ENABLED_AND_AUTHORIZED && !permissionChecker.hasLimitedAccess(SourceType.PHOTO)
    }

    override fun observeNewItems(): Flow<SourceItem> = _newItemsFlow.asSharedFlow()

    suspend fun activateWatching() = withContext(dispatchers.io) {
        activationMutex.withLock {
            if (isWatching) return@withContext
            if (!isAvailable()) {
                CayanaLogger.d("PhotoWatcher", "Cannot start watching photos: permission not authorized or limited.")
                return@withContext
            }

            val settings = settingsRepository.getSettings().first()
            val needsBaseline = settings.photoWatcherStatus == SourceWatcherStatus.UNINITIALIZED ||
                    settings.photoWatcherStatus == SourceWatcherStatus.DISABLED

            if (needsBaseline) {
                CayanaLogger.i("PhotoWatcher", "Establishing baseline BEFORE registering watcher (status=${settings.photoWatcherStatus}).")
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
                CayanaLogger.i("PhotoWatcher", "Started ContentObserver for MediaStore photos")

                // 2. Process-death persistent background path: WorkManager trigger
                PhotoIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)

                // 3. Catch-up on process restart with existing active status
                if (!needsBaseline) {
                    coordinator.processPendingPhotos()
                }
            } catch (e: Exception) {
                CayanaLogger.w("PhotoWatcher", "Failed to register ContentObserver: ${e.javaClass.simpleName}")
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
                CayanaLogger.w("PhotoWatcher", "Error unregistering ContentObserver: ${e.message}")
            }
            isWatching = false
        }
        PhotoIngestWorker.cancelTrigger(context)
        CayanaLogger.i("PhotoWatcher", "Stopped watching MediaStore photos and cancelled WorkManager trigger")
    }
}
