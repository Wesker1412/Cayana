package com.cayana.source.recording

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.logging.PrivacySanitizer
import com.cayana.core.notification.NotificationHelper
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.processing.SpeechToTextEngine
import com.cayana.processing.SttChunkResult
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.ceil

/**
 * Coordinates the Voice Recording ingestion pipeline:
 * MediaStore query -> Classifier (rejects music/podcasts) -> Immediate Memory persistence ->
 * Chunked on-device STT with durable checkpointing -> Transcript assembly -> Notification.
 *
 * Strict boundaries:
 * - Persists MemoryItem BEFORE STT completion.
 * - Resumes from checkpoint on process restart (never restarts chunk 0 if chunk 1+ completed).
 * - Never mutates Android Calendar.
 * - Never logs private transcript content.
 */
class RecordingProcessingCoordinator(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val sttEngine: SpeechToTextEngine,
    private val dispatchers: CoroutineDispatchers = AppDispatchers(),
    private val chunkDurationMs: Long = RecordingConfig.CHUNK_DURATION_MS,
    private val sourceExistenceValidator: com.cayana.source.SourceExistenceValidator = com.cayana.source.SourceExistenceValidator(context, permissionChecker)
) {
    private val processingMutex = Mutex()
    var mediaStoreVersionProvider: (Context) -> String? = { getMediaStoreVersion(it) }

    suspend fun processPendingRecordings(): List<MemoryItem> = withContext(dispatchers.io) {
        processingMutex.withLock {
            val settings = settingsRepository.getSettings().first()
            val isEnabled = settings.enabledSources.contains(SourceType.RECORDING)

            // 1. Strict Permission Boundary Check
            val status = permissionChecker.getSourceStatus(
                sourceType = SourceType.RECORDING,
                isEnabled = isEnabled,
                isDenied = false,
                customUri = null
            )
            if (status != SourceStatus.ENABLED_AND_AUTHORIZED) {
                CayanaLogger.d("RecordingCoordinator", "Recording source not fully authorized ($status). Skipping ingestion.")
                return@withLock emptyList()
            }

            val currentVersion = mediaStoreVersionProvider(context)
            val savedVersion = settings.recordingMediaStoreVersion

            // 2. MediaStore Version Reset Boundary Check
            if (savedVersion != null && currentVersion != null && savedVersion != currentVersion) {
                CayanaLogger.w("RecordingCoordinator", "MediaStore version changed ($savedVersion -> $currentVersion). Re-establishing baseline.")
                establishBaseline(forceNew = true)
                return@withLock emptyList()
            }

            // 3. Lifecycle Baseline Check
            if (settings.recordingWatcherStatus == SourceWatcherStatus.UNINITIALIZED ||
                settings.recordingWatcherStatus == SourceWatcherStatus.DISABLED) {
                CayanaLogger.i("RecordingCoordinator", "Recording watcher status is ${settings.recordingWatcherStatus}. Establishing initial baseline.")
                establishBaseline(forceNew = true)
                return@withLock emptyList()
            }

            val lastProcessedId = settings.lastRecordingMediaId
            val newMemories = mutableListOf<MemoryItem>()
            var committedCursorId = lastProcessedId

            val projection = buildList {
                add(MediaStore.Audio.Media._ID)
                add(MediaStore.Audio.Media.DISPLAY_NAME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    add(MediaStore.Audio.Media.DATA)
                }
                add(MediaStore.Audio.Media.MIME_TYPE)
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.DATE_ADDED)
                add(MediaStore.Audio.Media.DATE_MODIFIED)
                add(MediaStore.Audio.Media.SIZE)
                add(MediaStore.Audio.Media.IS_MUSIC)
                add(MediaStore.Audio.Media.IS_PODCAST)
                add(MediaStore.Audio.Media.IS_RINGTONE)
                add(MediaStore.Audio.Media.IS_NOTIFICATION)
                add(MediaStore.Audio.Media.IS_ALARM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(MediaStore.Audio.AudioColumns.IS_RECORDING)
                }
            }.toTypedArray()

            val selection = "${MediaStore.Audio.Media._ID} > ?"
            val selectionArgs = arrayOf(lastProcessedId.toString())
            val sortOrder = "${MediaStore.Audio.Media._ID} ASC"

            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

                if (cursor != null) {
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    if (idCol < 0) {
                        CayanaLogger.w("RecordingCoordinator", "_ID column missing from MediaStore query")
                        return@withLock emptyList()
                    }
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else {
                        cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    }
                    val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                    val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val musicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                    val podcastCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_PODCAST)
                    val ringtoneCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                    val notifCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
                    val alarmCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                    val recCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        cursor.getColumnIndex(MediaStore.Audio.AudioColumns.IS_RECORDING)
                    } else -1

                    while (cursor.moveToNext()) {
                        val mediaId = cursor.getLong(idCol)
                        val displayName = if (nameCol >= 0) cursor.getString(nameCol) else null
                        val relativePath = if (pathCol >= 0) cursor.getString(pathCol) else null
                        val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                        val rawDuration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                        val dateAddedSec = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val isMusic = if (musicCol >= 0) cursor.getInt(musicCol) != 0 else false
                        val isPodcast = if (podcastCol >= 0) cursor.getInt(podcastCol) != 0 else false
                        val isRingtone = if (ringtoneCol >= 0) cursor.getInt(ringtoneCol) != 0 else false
                        val isNotification = if (notifCol >= 0) cursor.getInt(notifCol) != 0 else false
                        val isAlarm = if (alarmCol >= 0) cursor.getInt(alarmCol) != 0 else false
                        val isRec = if (recCol >= 0) cursor.getInt(recCol) != 0 else null

                        // 4. Recording Classification (Strict Music filtering)
                        if (!RecordingClassifier.isRecording(
                                relativePath = relativePath,
                                displayName = displayName,
                                mimeType = mimeType,
                                isRecordingColumn = isRec,
                                isMusic = isMusic,
                                isPodcast = isPodcast,
                                isRingtone = isRingtone,
                                isNotification = isNotification,
                                isAlarm = isAlarm
                            )) {
                            // Non-recording audio: safe to advance cursor past it
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            continue
                        }

                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            mediaId
                        )
                        val sourceUriString = contentUri.toString()

                        // 5. Deduplication Check
                        val existing = memoryRepository.getMemoryBySourceUri(sourceUriString)
                        if (existing != null) {
                            CayanaLogger.d("RecordingCoordinator", "Recording already ingested: ${PrivacySanitizer.sanitizeUri(sourceUriString)}")
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            continue
                        }

                        val capturedAt = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis()
                        val durationMs = if (rawDuration > 0) rawDuration else sttEngine.getAudioDurationMs(context, sourceUriString)
                        val totalChunks = maxOf(1, ceil(maxOf(1L, durationMs).toDouble() / chunkDurationMs).toInt())

                        val initialState = if (sttEngine.isModelAvailable) {
                            ProcessingState.PROCESSING
                        } else {
                            ProcessingState.WAITING_FOR_MODEL
                        }

                        val deterministicId = UUID.nameUUIDFromBytes(sourceUriString.toByteArray()).toString()
                        val metadata = mutableMapOf<String, String>().apply {
                            put("mediaStoreId", mediaId.toString())
                            displayName?.let { put("displayName", it) }
                            relativePath?.let { put("relativePath", it) }
                            mimeType?.let { put("mimeType", it) }
                            put("contentLength", size.toString())
                            put("durationMs", durationMs.toString())
                            put("totalChunks", totalChunks.toString())
                            put("completedChunks", "0")
                            put("sttEngine", sttEngine.engineName)
                        }

                        val initialMemoryItem = MemoryItem(
                            id = deterministicId,
                            sourceType = SourceType.RECORDING,
                            createdAt = System.currentTimeMillis(),
                            capturedAt = capturedAt,
                            title = deriveTitle(null, displayName),
                            rawText = null,
                            normalizedText = null,
                            sourceUri = sourceUriString,
                            sourceUrl = null,
                            sourceExists = true,
                            metadata = metadata,
                            processingState = initialState
                        )

                        // 6. IMMEDIATE INSERTION (Section 18: Persisted BEFORE STT finishes)
                        try {
                            memoryRepository.saveMemory(initialMemoryItem)
                            newMemories.add(initialMemoryItem)
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            CayanaLogger.i("RecordingCoordinator", "Persisted initial recording memory: $deterministicId (totalChunks=$totalChunks)")
                        } catch (e: Exception) {
                            CayanaLogger.e("RecordingCoordinator", "Failed to persist initial recording memory for mediaId $mediaId: ${e.message}")
                            break
                        }

                        // 7. Execute Chunked Transcription if model is available
                        if (initialState == ProcessingState.PROCESSING) {
                            val completedMemory = processChunksForMemory(initialMemoryItem, durationMs, totalChunks)
                            if (completedMemory.processingState == ProcessingState.COMPLETED ||
                                completedMemory.processingState == ProcessingState.COMPLETED_WITHOUT_TEXT) {
                                NotificationHelper.showMemoryIngestedNotification(context, completedMemory)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                CayanaLogger.w("RecordingCoordinator", "Error reading MediaStore: ${e.javaClass.simpleName}")
            } finally {
                cursor?.close()
            }

            // 8. Advance high-water mark cursor
            if (committedCursorId > lastProcessedId) {
                settingsRepository.updateLastRecordingMediaId(committedCursorId)
            }

            if (savedVersion == null && currentVersion != null) {
                settingsRepository.updateRecordingMediaStoreVersion(currentVersion)
            }

            try {
                reconcileDeletedRecordings()
            } catch (e: Exception) {
                CayanaLogger.w("RecordingCoordinator", "Failed reconciling deleted recordings: ${e.message}")
            }

            newMemories
        }
    }

    /**
     * Resumes transcription for in-flight recordings after process restart or failure recovery.
     * Starts from the latest completed chunk index; never restarts from chunk 0 if progress exists.
     */
    suspend fun reconcileInFlightRecordings(): Int = withContext(dispatchers.io) {
        processingMutex.withLock {
            val allMemories = memoryRepository.getAllMemories().first()
            val inFlightItems = allMemories.filter {
                it.sourceType == SourceType.RECORDING &&
                        (it.processingState == ProcessingState.PROCESSING ||
                         it.processingState == ProcessingState.FAILED_RETRYABLE ||
                         (it.processingState == ProcessingState.WAITING_FOR_MODEL && sttEngine.isModelAvailable))
            }

            var processedCount = 0
            for (item in inFlightItems) {
                val durationMs = item.metadata["durationMs"]?.toLongOrNull() ?: 0L
                val totalChunks = item.metadata["totalChunks"]?.toIntOrNull() ?: 1
                val updated = processChunksForMemory(item, durationMs, totalChunks)
                if (updated.processingState == ProcessingState.COMPLETED ||
                    updated.processingState == ProcessingState.COMPLETED_WITHOUT_TEXT) {
                    NotificationHelper.showMemoryIngestedNotification(context, updated)
                    processedCount++
                }
            }
            processedCount
        }
    }

    /**
     * Chunked processing core with durable checkpointing per chunk.
     */
    private suspend fun processChunksForMemory(
        memoryItem: MemoryItem,
        durationMs: Long,
        totalChunks: Int
    ): MemoryItem {
        val uriString = memoryItem.sourceUri ?: return memoryItem
        val currentMeta = memoryItem.metadata.toMutableMap()
        val startChunk = currentMeta["completedChunks"]?.toIntOrNull() ?: 0

        // Check if source file is still accessible
        val existence = sourceExistenceValidator.checkSourceExistence(uriString, SourceType.RECORDING)
        when (existence) {
            is com.cayana.source.SourceExistence.Missing -> {
                CayanaLogger.i("RecordingCoordinator", "Source audio definitively missing for ${memoryItem.id}. Halting transcription.")
                val finalItem = memoryItem.copy(
                    sourceExists = false,
                    processingState = if (startChunk > 0) ProcessingState.COMPLETED else ProcessingState.FAILED_PERMANENT
                )
                memoryRepository.saveMemory(finalItem)
                return finalItem
            }
            is com.cayana.source.SourceExistence.Unavailable -> {
                CayanaLogger.w("RecordingCoordinator", "Source audio temporarily unavailable for ${memoryItem.id}. Preserving state as FAILED_RETRYABLE.")
                val pausedItem = memoryItem.copy(processingState = ProcessingState.FAILED_RETRYABLE)
                memoryRepository.saveMemory(pausedItem)
                return pausedItem
            }
            is com.cayana.source.SourceExistence.Exists -> {
                // proceed
            }
        }

        if (!sttEngine.isModelAvailable) {
            val waitingItem = memoryItem.copy(processingState = ProcessingState.WAITING_FOR_MODEL)
            memoryRepository.saveMemory(waitingItem)
            return waitingItem
        }

        var currentItem = memoryItem.copy(processingState = ProcessingState.PROCESSING)
        for (chunkIdx in startChunk until totalChunks) {
            val startMs = chunkIdx * chunkDurationMs
            val remainingMs = maxOf(0L, durationMs - startMs)
            val currentChunkDuration = if (remainingMs > 0 && remainingMs < chunkDurationMs) remainingMs else chunkDurationMs

            CayanaLogger.d("RecordingCoordinator", "Transcribing chunk $chunkIdx/$totalChunks for memory ${currentItem.id}")
            when (val chunkResult = sttEngine.transcribeChunk(context, uriString, chunkIdx, startMs, currentChunkDuration)) {
                is SttChunkResult.Success -> {
                    currentMeta["chunk_$chunkIdx"] = chunkResult.text
                    val nextCompleted = chunkIdx + 1
                    currentMeta["completedChunks"] = nextCompleted.toString()
                    currentItem = currentItem.copy(metadata = currentMeta.toMap())
                    // Durable checkpoint after every chunk!
                    memoryRepository.saveMemory(currentItem)
                }
                is SttChunkResult.Failure -> {
                    CayanaLogger.w("RecordingCoordinator", "Chunk $chunkIdx failed: ${chunkResult.error.message}")
                    val retryable = chunkResult.isRetryable
                    val failureState = if (retryable) ProcessingState.FAILED_RETRYABLE else ProcessingState.FAILED_PERMANENT
                    currentItem = currentItem.copy(processingState = failureState, metadata = currentMeta.toMap())
                    memoryRepository.saveMemory(currentItem)
                    return currentItem
                }
                is SttChunkResult.ModelUnavailable -> {
                    currentItem = currentItem.copy(processingState = ProcessingState.WAITING_FOR_MODEL, metadata = currentMeta.toMap())
                    memoryRepository.saveMemory(currentItem)
                    return currentItem
                }
            }
        }

        // All chunks successfully completed -> assemble transcript
        val assembledText = buildString {
            for (i in 0 until totalChunks) {
                val chunkText = currentMeta["chunk_$i"]
                if (!chunkText.isNullOrBlank()) {
                    if (isNotEmpty()) append(" ")
                    append(chunkText.trim())
                }
            }
        }.trim()

        val (finalState, finalRawText) = if (assembledText.isNotBlank()) {
            Pair(ProcessingState.COMPLETED, assembledText)
        } else {
            Pair(ProcessingState.COMPLETED_WITHOUT_TEXT, null)
        }

        val finalTitle = deriveTitle(finalRawText, currentMeta["displayName"])
        val finalMemory = currentItem.copy(
            rawText = finalRawText,
            normalizedText = finalRawText,
            title = finalTitle,
            processingState = finalState,
            metadata = currentMeta.toMap()
        )
        memoryRepository.saveMemory(finalMemory)
        CayanaLogger.i("RecordingCoordinator", "Successfully finished recording transcription for ${finalMemory.id}")
        return finalMemory
    }

    suspend fun establishBaseline(forceNew: Boolean = false): Long = withContext(dispatchers.io) {
        val currentSettings = settingsRepository.getSettings().first()
        val currentVersion = mediaStoreVersionProvider(context)
        if (!forceNew && currentSettings.lastRecordingMediaId > 0L && currentVersion != null && currentSettings.recordingMediaStoreVersion == currentVersion) {
            return@withContext currentSettings.lastRecordingMediaId
        }

        var maxId = 0L
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val sortOrder = "${MediaStore.Audio.Media._ID} DESC"
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    maxId = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                }
            }
        } catch (_: Exception) {}

        settingsRepository.updateLastRecordingMediaId(maxId)
        if (currentVersion != null) {
            settingsRepository.updateRecordingMediaStoreVersion(currentVersion)
        }
        settingsRepository.updateRecordingWatcherStatus(SourceWatcherStatus.ACTIVE)
        maxId
    }

    suspend fun reconcileDeletedRecordings(batchSize: Int = 25): Int = withContext(dispatchers.io) {
        val settings = settingsRepository.getSettings().first()
        val cursorTimestamp = settings.lastRecordingReconciledCapturedAt
        val recordings = memoryRepository.getMemoriesForReconciliation(
            sourceType = SourceType.RECORDING,
            cursorTimestamp = cursorTimestamp,
            limit = batchSize
        )
        if (recordings.isEmpty()) return@withContext 0

        var updatedCount = 0
        var minTimestamp = cursorTimestamp
        for (item in recordings) {
            minTimestamp = minOf(minTimestamp, item.capturedAt)
            val uriString = item.sourceUri ?: continue
            val existence = sourceExistenceValidator.checkSourceExistence(uriString, SourceType.RECORDING)
            when (existence) {
                is com.cayana.source.SourceExistence.Missing -> {
                    CayanaLogger.i("RecordingCoordinator", "Audio source file deleted for memory ${item.id}, updating sourceExists=false")
                    memoryRepository.markSourceExists(item.id, false)
                    updatedCount++
                }
                is com.cayana.source.SourceExistence.Unavailable -> {
                    CayanaLogger.d("RecordingCoordinator", "Audio source unavailable for memory ${item.id}, preserving sourceExists")
                }
                is com.cayana.source.SourceExistence.Exists -> {
                    // source exists
                }
            }
        }
        val newCursor = if (recordings.size < batchSize) Long.MAX_VALUE else minTimestamp
        settingsRepository.updateLastRecordingReconciledCapturedAt(newCursor)
        updatedCount
    }

    private fun deriveTitle(transcript: String?, displayName: String?): String {
        if (!transcript.isNullOrBlank()) {
            val firstLine = transcript.lines().firstOrNull { it.isNotBlank() }?.trim()
            if (!firstLine.isNullOrBlank()) {
                return if (firstLine.length > 40) firstLine.take(37) + "..." else firstLine
            }
        }
        return displayName?.substringBeforeLast(".") ?: "語音錄音"
    }

    companion object {
        fun getMediaStoreVersion(context: Context): String? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val version = MediaStore.getVersion(context)
                    if (version.isNullOrBlank()) null else version
                } catch (e: Exception) {
                    CayanaLogger.w("RecordingCoordinator", "Failed to get MediaStore version: ${e.message}")
                    null
                }
            } else {
                "pre_q"
            }
        }
    }
}
