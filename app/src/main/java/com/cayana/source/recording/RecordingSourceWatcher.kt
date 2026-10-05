package com.cayana.source.recording

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
 * Watcher implementation for MediaStore Audio/Recordings.
 * Uses ContentObserver as the alive fast path, and WorkManager ContentUriTrigger
 * as the persistent background wake path.
 */
class RecordingSourceWatcher(
    private val context: Context,
    private val permissionChecker: PermissionChecker,
    private val coordinator: RecordingProcessingCoordinator,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers = AppDispatchers()
) : SourceWatcher {

    override val sourceType: SourceType = SourceType.RECORDING

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val _newItemsFlow = MutableSharedFlow<SourceItem>(extraBufferCapacity = 64)
    private val activationMutex = Mutex()
    @Volatile
    private var isWatching = false

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            CayanaLogger.d("RecordingWatcher", "ContentObserver onChange detected Audio update")
            scope.launch {
                val newMemories = coordinator.processPendingRecordings()
                newMemories.forEach { memory ->
                    val sourceItem = SourceItem(
                        id = memory.id,
                        sourceType = SourceType.RECORDING,
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
            sourceType = SourceType.RECORDING,
            isEnabled = true,
            isDenied = false,
            customUri = null
        )
        return status == SourceStatus.ENABLED_AND_AUTHORIZED
    }

    override fun observeNewItems(): Flow<SourceItem> = _newItemsFlow.asSharedFlow()

    suspend fun activateWatching() = withContext(dispatchers.io) {
        activationMutex.withLock {
            if (isWatching) return@withContext
            if (!isAvailable()) {
                CayanaLogger.d("RecordingWatcher", "Cannot start watching recordings: permission not authorized.")
                return@withContext
            }

            val settings = settingsRepository.getSettings().first()
            val needsBaseline = settings.recordingWatcherStatus == SourceWatcherStatus.UNINITIALIZED ||
                    settings.recordingWatcherStatus == SourceWatcherStatus.DISABLED

            if (needsBaseline) {
                CayanaLogger.i("RecordingWatcher", "Establishing baseline BEFORE registering watcher (status=${settings.recordingWatcherStatus}).")
                coordinator.establishBaseline(forceNew = true)
            }

            try {
                // 1. Process-alive fast path: ContentObserver
                context.contentResolver.registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    true,
                    contentObserver
                )
                isWatching = true
                CayanaLogger.i("RecordingWatcher", "Started ContentObserver for MediaStore recordings")

                // 2. Process-death persistent background path: WorkManager trigger
                RecordingIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)

                // 3. Catch-up on process restart with existing active status
                if (!needsBaseline) {
                    coordinator.processPendingRecordings()
                    coordinator.reconcileInFlightRecordings()
                }
            } catch (e: Exception) {
                CayanaLogger.w("RecordingWatcher", "Failed to register ContentObserver: ${e.javaClass.simpleName}")
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
                CayanaLogger.w("RecordingWatcher", "Error unregistering ContentObserver: ${e.message}")
            }
            isWatching = false
        }
        RecordingIngestWorker.cancelTrigger(context)
        CayanaLogger.i("RecordingWatcher", "Stopped watching MediaStore recordings and cancelled WorkManager trigger")
    }
}
