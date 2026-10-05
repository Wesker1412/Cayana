package com.cayana.source.photo

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.logging.PrivacySanitizer
import com.cayana.core.notification.NotificationHelper
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.processing.OcrEngine
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Coordinates the Photo ingestion pipeline:
 * MediaStore query -> Classifier -> Dedup -> Local OCR -> Unified Memory persistence -> Notification.
 * Strictly independent cursor from Screenshot watcher.
 * Never executes Calendar side-effects.
 */
class PhotoProcessingCoordinator(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val ocrEngine: OcrEngine,
    private val dispatchers: CoroutineDispatchers = AppDispatchers(),
    private val sourceExistenceValidator: com.cayana.source.SourceExistenceValidator = com.cayana.source.SourceExistenceValidator(context, permissionChecker)
) {
    private val processingMutex = Mutex()
    var mediaStoreVersionProvider: (Context) -> String? = { getMediaStoreVersion(it) }

    suspend fun processPendingPhotos(): List<MemoryItem> = withContext(dispatchers.io) {
        processingMutex.withLock {
            val settings = settingsRepository.getSettings().first()
            val isEnabled = settings.enabledSources.contains(SourceType.PHOTO)

            // 1. Strict Permission & Limited Access Boundary Check
            val status = permissionChecker.getSourceStatus(
                sourceType = SourceType.PHOTO,
                isEnabled = isEnabled,
                isDenied = false,
                customUri = null
            )
            if (status != SourceStatus.ENABLED_AND_AUTHORIZED || permissionChecker.hasLimitedAccess(SourceType.PHOTO)) {
                CayanaLogger.d("PhotoCoordinator", "Photo source not fully authorized ($status). Skipping ingestion.")
                return@withLock emptyList()
            }

            val currentVersion = mediaStoreVersionProvider(context)
            val savedVersion = settings.photoMediaStoreVersion

            // 2. MediaStore Version Reset Boundary Check
            if (savedVersion != null && currentVersion != null && savedVersion != currentVersion) {
                CayanaLogger.w("PhotoCoordinator", "MediaStore version changed ($savedVersion -> $currentVersion). Re-establishing baseline.")
                establishBaseline(forceNew = true)
                return@withLock emptyList()
            }

            // 3. Lifecycle Baseline Check
            if (settings.photoWatcherStatus == SourceWatcherStatus.UNINITIALIZED ||
                settings.photoWatcherStatus == SourceWatcherStatus.DISABLED) {
                CayanaLogger.i("PhotoCoordinator", "Photo watcher status is ${settings.photoWatcherStatus}. Establishing initial baseline.")
                establishBaseline(forceNew = true)
                return@withLock emptyList()
            }

            val lastProcessedId = settings.lastPhotoMediaId
            val newMemories = mutableListOf<MemoryItem>()
            var committedCursorId = lastProcessedId

            val projection = buildList {
                add(MediaStore.Images.Media._ID)
                add(MediaStore.Images.Media.DISPLAY_NAME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    add(MediaStore.Images.Media.DATA)
                }
                add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Images.Media.MIME_TYPE)
                add(MediaStore.Images.Media.DATE_ADDED)
                add(MediaStore.Images.Media.DATE_TAKEN)
                add(MediaStore.Images.Media.SIZE)
                add(MediaStore.Images.Media.WIDTH)
                add(MediaStore.Images.Media.HEIGHT)
                add(MediaStore.Images.Media.ORIENTATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Images.Media.IS_PENDING)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.Images.Media.IS_TRASHED)
                }
            }.toTypedArray()

            val selection = "${MediaStore.Images.Media._ID} > ?"
            val selectionArgs = arrayOf(lastProcessedId.toString())
            val sortOrder = "${MediaStore.Images.Media._ID} ASC"

            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

                if (cursor != null) {
                    val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    if (idCol < 0) {
                        CayanaLogger.w("PhotoCoordinator", "_ID column missing from MediaStore query")
                        return@withLock emptyList()
                    }
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    } else {
                        cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    }
                    val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val orientationCol = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
                    val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    } else -1
                    val trashedCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cursor.getColumnIndex(MediaStore.Images.Media.IS_TRASHED)
                    } else -1

                    while (cursor.moveToNext()) {
                        val mediaId = cursor.getLong(idCol)
                        val displayName = if (nameCol >= 0) cursor.getString(nameCol) else null
                        val relativePath = if (pathCol >= 0) cursor.getString(pathCol) else null
                        val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) else null
                        val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                        val dateAddedSec = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L
                        val dateTakenMs = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                        val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                        val orientation = if (orientationCol >= 0) cursor.getInt(orientationCol) else 0
                        val isPending = if (pendingCol >= 0) cursor.getInt(pendingCol) != 0 else false
                        val isTrashed = if (trashedCol >= 0) cursor.getInt(trashedCol) != 0 else false

                        // 4. Photo Classification
                        if (!PhotoClassifier.isPhoto(relativePath, displayName, bucketName, mimeType, isPending, isTrashed)) {
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            continue
                        }

                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            mediaId
                        )
                        val sourceUriString = contentUri.toString()

                        // 5. Deduplication Check
                        val existing = memoryRepository.getMemoryBySourceUri(sourceUriString)
                        if (existing != null) {
                            CayanaLogger.d("PhotoCoordinator", "Photo already ingested: ${PrivacySanitizer.sanitizeUri(sourceUriString)}")
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            continue
                        }

                        // Calculate timestamp
                        val capturedAt = if (dateTakenMs > 0) {
                            dateTakenMs
                        } else if (dateAddedSec > 0) {
                            dateAddedSec * 1000L
                        } else {
                            System.currentTimeMillis()
                        }

                        // 6. Local OCR Processing
                        val (rawText, processingState) = try {
                            when (val ocrResult = ocrEngine.processImage(sourceUriString)) {
                                is Result.Success -> {
                                    val text = ocrResult.data.fullText.trim()
                                    if (text.isNotBlank()) {
                                        Pair(text, ProcessingState.COMPLETED)
                                    } else {
                                        Pair(null, ProcessingState.COMPLETED_WITHOUT_TEXT)
                                    }
                                }
                                is Result.Error -> {
                                    CayanaLogger.w("PhotoCoordinator", "OCR failed for ${PrivacySanitizer.sanitizeUri(sourceUriString)}: ${ocrResult.exception.message}")
                                    Pair(null, ProcessingState.FAILED_RETRYABLE)
                                }
                                Result.Loading -> Pair(null, ProcessingState.FAILED_RETRYABLE)
                            }
                        } catch (e: Exception) {
                            CayanaLogger.w("PhotoCoordinator", "OCR threw exception: ${e.message}")
                            Pair(null, ProcessingState.FAILED_RETRYABLE)
                        }

                        val title = deriveTitle(rawText, displayName)
                        val deterministicId = UUID.nameUUIDFromBytes(sourceUriString.toByteArray()).toString()

                        val metadata = mutableMapOf<String, String>().apply {
                            put("mediaStoreId", mediaId.toString())
                            displayName?.let { put("displayName", it) }
                            relativePath?.let { put("relativePath", it) }
                            mimeType?.let { put("mimeType", it) }
                            put("contentLength", size.toString())
                            put("width", width.toString())
                            put("height", height.toString())
                            put("orientation", orientation.toString())
                            put("location", "unavailable")
                        }

                        val memoryItem = MemoryItem(
                            id = deterministicId,
                            sourceType = SourceType.PHOTO,
                            createdAt = System.currentTimeMillis(),
                            capturedAt = capturedAt,
                            title = title,
                            rawText = rawText,
                            normalizedText = rawText,
                            sourceUri = sourceUriString,
                            sourceUrl = null,
                            sourceExists = true,
                            metadata = metadata,
                            processingState = processingState
                        )

                        // 7. Persist to Room
                        try {
                            memoryRepository.saveMemory(memoryItem)
                            newMemories.add(memoryItem)
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            CayanaLogger.i("PhotoCoordinator", "Successfully ingested photo memory: $deterministicId")
                        } catch (e: Exception) {
                            CayanaLogger.e("PhotoCoordinator", "Failed to persist memory for mediaId $mediaId: ${e.message}")
                            break
                        }

                        // 8. Notification (Never trigger calendar)
                        NotificationHelper.showMemoryIngestedNotification(context, memoryItem)
                    }
                }
            } catch (e: Exception) {
                CayanaLogger.w("PhotoCoordinator", "Error reading MediaStore: ${e.javaClass.simpleName}")
            } finally {
                cursor?.close()
            }

            // 9. Advance high-water mark cursor
            if (committedCursorId > lastProcessedId) {
                settingsRepository.updateLastPhotoMediaId(committedCursorId)
            }

            if (savedVersion == null && currentVersion != null) {
                settingsRepository.updatePhotoMediaStoreVersion(currentVersion)
            }

            try {
                retryPendingOcrInternal()
            } catch (e: Exception) {
                CayanaLogger.w("PhotoCoordinator", "Failed retrying pending photo OCR: ${e.message}")
            }

            try {
                reconcileDeletedPhotos()
            } catch (e: Exception) {
                CayanaLogger.w("PhotoCoordinator", "Failed reconciling deleted photos: ${e.message}")
            }

            newMemories
        }
    }

    suspend fun establishBaseline(forceNew: Boolean = false): Long = withContext(dispatchers.io) {
        val currentSettings = settingsRepository.getSettings().first()
        val currentVersion = mediaStoreVersionProvider(context)
        if (!forceNew && currentSettings.lastPhotoMediaId > 0L && currentVersion != null && currentSettings.photoMediaStoreVersion == currentVersion) {
            return@withContext currentSettings.lastPhotoMediaId
        }

        var maxId = 0L
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media._ID} DESC"
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    maxId = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                }
            }
        } catch (_: Exception) {}

        settingsRepository.updateLastPhotoMediaId(maxId)
        if (currentVersion != null) {
            settingsRepository.updatePhotoMediaStoreVersion(currentVersion)
        }
        settingsRepository.updatePhotoWatcherStatus(SourceWatcherStatus.ACTIVE)
        maxId
    }

    suspend fun reconcileDeletedPhotos(batchSize: Int = 25): Int = withContext(dispatchers.io) {
        val settings = settingsRepository.getSettings().first()
        val cursorTimestamp = settings.lastPhotoReconciledCapturedAt
        val photos = memoryRepository.getMemoriesForReconciliation(
            sourceType = SourceType.PHOTO,
            cursorTimestamp = cursorTimestamp,
            limit = batchSize
        )
        if (photos.isEmpty()) return@withContext 0

        var updatedCount = 0
        var minTimestamp = cursorTimestamp
        for (item in photos) {
            minTimestamp = minOf(minTimestamp, item.capturedAt)
            val uriString = item.sourceUri ?: continue
            val existence = sourceExistenceValidator.checkSourceExistence(uriString, SourceType.PHOTO)
            when (existence) {
                is com.cayana.source.SourceExistence.Missing -> {
                    CayanaLogger.i("PhotoCoordinator", "Photo source file deleted for memory ${item.id}, updating sourceExists=false")
                    memoryRepository.markSourceExists(item.id, false)
                    updatedCount++
                }
                is com.cayana.source.SourceExistence.Unavailable -> {
                    CayanaLogger.d("PhotoCoordinator", "Photo source unavailable for memory ${item.id}, preserving sourceExists")
                }
                is com.cayana.source.SourceExistence.Exists -> {
                    // source exists
                }
            }
        }
        val newCursor = if (photos.size < batchSize) Long.MAX_VALUE else minTimestamp
        settingsRepository.updateLastPhotoReconciledCapturedAt(newCursor)
        updatedCount
    }

    suspend fun retryPendingOcr(): Int = withContext(dispatchers.io) {
        processingMutex.withLock {
            retryPendingOcrInternal()
        }
    }

    private suspend fun retryPendingOcrInternal(): Int {
        val allMemories = memoryRepository.getAllMemories().first()
        val retryablePhotos = allMemories
            .filter { it.sourceType == SourceType.PHOTO && it.processingState == ProcessingState.FAILED_RETRYABLE }
            .take(5)
        var count = 0

        for (item in retryablePhotos) {
            val uriString = item.sourceUri
            if (uriString.isNullOrBlank()) {
                memoryRepository.saveMemory(item.copy(processingState = ProcessingState.FAILED_PERMANENT, sourceExists = false))
                continue
            }

            val existence = sourceExistenceValidator.checkSourceExistence(uriString, SourceType.PHOTO)
            if (existence is com.cayana.source.SourceExistence.Missing) {
                memoryRepository.saveMemory(item.copy(processingState = ProcessingState.FAILED_PERMANENT, sourceExists = false))
                continue
            }
            if (existence is com.cayana.source.SourceExistence.Unavailable) {
                continue
            }

            val currentRetry = item.metadata["ocrRetryCount"]?.toIntOrNull() ?: 0
            val nextRetry = currentRetry + 1
            if (nextRetry > 5) {
                val updatedMeta = item.metadata.toMutableMap().apply { put("ocrRetryCount", nextRetry.toString()) }
                memoryRepository.saveMemory(item.copy(processingState = ProcessingState.FAILED_PERMANENT, metadata = updatedMeta))
                continue
            }

            try {
                when (val result = ocrEngine.processImage(uriString)) {
                    is Result.Success -> {
                        val text = result.data.fullText.trim()
                        val (newText, newState) = if (text.isNotBlank()) {
                            Pair(text, ProcessingState.COMPLETED)
                        } else {
                            Pair(null, ProcessingState.COMPLETED_WITHOUT_TEXT)
                        }
                        val updatedMeta = item.metadata.toMutableMap().apply { put("ocrRetryCount", nextRetry.toString()) }
                        val updated = item.copy(
                            rawText = newText,
                            normalizedText = newText,
                            title = deriveTitle(newText, item.metadata["displayName"]),
                            metadata = updatedMeta,
                            processingState = newState
                        )
                        memoryRepository.saveMemory(updated)
                        count++
                    }
                    is Result.Error, Result.Loading -> {
                        val state = if (nextRetry >= 5) ProcessingState.FAILED_PERMANENT else ProcessingState.FAILED_RETRYABLE
                        val updatedMeta = item.metadata.toMutableMap().apply { put("ocrRetryCount", nextRetry.toString()) }
                        memoryRepository.saveMemory(item.copy(processingState = state, metadata = updatedMeta))
                    }
                }
            } catch (_: Exception) {
                val state = if (nextRetry >= 5) ProcessingState.FAILED_PERMANENT else ProcessingState.FAILED_RETRYABLE
                val updatedMeta = item.metadata.toMutableMap().apply { put("ocrRetryCount", nextRetry.toString()) }
                memoryRepository.saveMemory(item.copy(processingState = state, metadata = updatedMeta))
            }
        }
        return count
    }

    private fun deriveTitle(rawText: String?, displayName: String?): String {
        if (!rawText.isNullOrBlank()) {
            val firstLine = rawText.lines().firstOrNull { it.isNotBlank() }?.trim()
            if (!firstLine.isNullOrBlank()) {
                return if (firstLine.length > 40) firstLine.take(37) + "..." else firstLine
            }
        }
        return displayName?.substringBeforeLast(".") ?: "相片"
    }

    companion object {
        fun getMediaStoreVersion(context: Context): String? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val version = MediaStore.getVersion(context)
                    if (version.isNullOrBlank()) null else version
                } catch (e: Exception) {
                    CayanaLogger.w("PhotoCoordinator", "Failed to get MediaStore version: ${e.message}")
                    null
                }
            } else {
                "pre_q"
            }
        }
    }
}
