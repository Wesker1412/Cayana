package com.cayana.source.screenshot

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.cayana.calendar.CalendarProcessingCoordinator
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Coordinates the full Screenshot pipeline:
 * MediaStore query -> Classifier -> Dedup -> OCR -> MemoryItem persistence -> Notification -> High-water mark update.
 */
class ScreenshotProcessingCoordinator(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val ocrEngine: OcrEngine,
    private val dispatchers: CoroutineDispatchers = AppDispatchers(),
    private val calendarProcessingCoordinator: CalendarProcessingCoordinator? = null
) {
    private val processingMutex = Mutex()
    var mediaStoreVersionProvider: (Context) -> String? = { getMediaStoreVersion(it) }

    /**
     * Inspects MediaStore for any new screenshots beyond the saved high-water mark,
     * ingests them into Room memory, posts minimalist notifications, and advances the cursor.
     */
    suspend fun processPendingScreenshots(): List<MemoryItem> = withContext(dispatchers.io) {
        processingMutex.withLock {
            val settings = settingsRepository.getSettings().first()
            val isEnabled = settings.enabledSources.contains(SourceType.SCREENSHOT)

            // 1. Strict Permission Boundary Check
            val status = permissionChecker.getSourceStatus(
                sourceType = SourceType.SCREENSHOT,
                isEnabled = isEnabled,
                isDenied = false,
                customUri = null
            )
            if (status != SourceStatus.ENABLED_AND_AUTHORIZED) {
                CayanaLogger.d("ScreenshotCoordinator", "Screenshots source not fully authorized ($status). Skipping ingestion.")
                return@withLock emptyList()
            }

            val currentVersion = mediaStoreVersionProvider(context)
            val savedVersion = settings.mediaStoreVersion

            // 2. MediaStore Version Reset Boundary Check (Item E & Item 6)
            if (savedVersion != null && currentVersion != null && savedVersion != currentVersion) {
                CayanaLogger.w("ScreenshotCoordinator", "MediaStore version changed ($savedVersion -> $currentVersion). Re-establishing baseline.")
                establishBaseline(forceNew = true)
                return@withLock emptyList()
            }

            // 3. Lifecycle Baseline Check (Item A)
            if (settings.screenshotWatcherStatus == com.cayana.ui.settings.repository.ScreenshotWatcherStatus.UNINITIALIZED ||
                settings.screenshotWatcherStatus == com.cayana.ui.settings.repository.ScreenshotWatcherStatus.DISABLED) {
                CayanaLogger.i("ScreenshotCoordinator", "Screenshot watcher status is ${settings.screenshotWatcherStatus}. Establishing initial baseline.")
                establishBaseline(forceNew = true)
                return@withLock emptyList()
            }

            val lastProcessedId = settings.lastScreenshotMediaId
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
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    } else {
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    }
                    val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                    while (cursor.moveToNext()) {
                        val mediaId = cursor.getLong(idCol)
                        val displayName = cursor.getString(nameCol)
                        val relativePath = cursor.getString(pathCol)
                        val bucketName = cursor.getString(bucketCol)
                        val mimeType = cursor.getString(mimeCol)
                        val dateAddedSec = cursor.getLong(dateAddedCol)
                        val dateTakenMs = cursor.getLong(dateTakenCol)
                        val size = cursor.getLong(sizeCol)

                        // 4. Screenshot Classification
                        if (!ScreenshotClassifier.isScreenshot(relativePath, displayName, bucketName, mimeType)) {
                            // Non-screenshot: safe to advance cursor past this item
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
                            CayanaLogger.d("ScreenshotCoordinator", "Item already ingested for uri: ${PrivacySanitizer.sanitizeUri(sourceUriString)}")
                            // Already in DB: safe to advance cursor past this item
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            continue
                        }

                        // Calculate capturedAt timestamp
                        val capturedAt = if (dateTakenMs > 0) {
                            dateTakenMs
                        } else if (dateAddedSec > 0) {
                            dateAddedSec * 1000L
                        } else {
                            System.currentTimeMillis()
                        }

                        // 6. Local OCR Processing (Item C: Distinguish empty text vs OCR error)
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
                                    CayanaLogger.w("ScreenshotCoordinator", "OCR failed for ${PrivacySanitizer.sanitizeUri(sourceUriString)}: ${ocrResult.exception.message}")
                                    Pair(null, ProcessingState.FAILED_RETRYABLE)
                                }
                                Result.Loading -> {
                                    Pair(null, ProcessingState.FAILED_RETRYABLE)
                                }
                            }
                        } catch (e: Exception) {
                            CayanaLogger.w("ScreenshotCoordinator", "OCR threw exception for ${PrivacySanitizer.sanitizeUri(sourceUriString)}: ${e.message}")
                            Pair(null, ProcessingState.FAILED_RETRYABLE)
                        }

                        val title = deriveTitle(rawText, displayName)

                        // 7. Build Unified MemoryItem with deterministic UUID
                        val deterministicId = UUID.nameUUIDFromBytes(sourceUriString.toByteArray()).toString()
                        val metadata = mutableMapOf<String, String>().apply {
                            put("mediaStoreId", mediaId.toString())
                            displayName?.let { put("displayName", it) }
                            relativePath?.let { put("relativePath", it) }
                            mimeType?.let { put("mimeType", it) }
                            put("contentLength", size.toString())
                        }

                        val memoryItem = MemoryItem(
                            id = deterministicId,
                            sourceType = SourceType.SCREENSHOT,
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

                        // 8. Persist to Room (Item B: Commit cursor ONLY after successful persist)
                        try {
                            memoryRepository.saveMemory(memoryItem)
                            newMemories.add(memoryItem)
                            committedCursorId = maxOf(committedCursorId, mediaId)
                            CayanaLogger.i("ScreenshotCoordinator", "Successfully ingested screenshot memory: $deterministicId")
                        } catch (e: Exception) {
                            CayanaLogger.e("ScreenshotCoordinator", "Failed to persist memory to Room for mediaId $mediaId: ${e.message}")
                            // Do NOT advance cursor past this mediaId. Break the batch so subsequent run will retry!
                            break
                        }

                        // 9. Calendar Extraction & Notification
                        val handledByCalendar = try {
                            calendarProcessingCoordinator?.process(memoryItem, rawText) ?: false
                        } catch (e: Exception) {
                            CayanaLogger.w("ScreenshotCoordinator", "Calendar processing error: ${e.message}")
                            false
                        }

                        if (!handledByCalendar) {
                            NotificationHelper.showMemoryIngestedNotification(context, memoryItem)
                        }
                    }
                }
            } catch (e: Exception) {
                CayanaLogger.w("ScreenshotCoordinator", "Error reading MediaStore: ${e.javaClass.simpleName}")
            } finally {
                cursor?.close()
            }

            // 10. Advance High-Water Mark Cursor to durable committed point
            if (committedCursorId > lastProcessedId) {
                settingsRepository.updateLastScreenshotMediaId(committedCursorId)
            }

            if (savedVersion == null && currentVersion != null) {
                settingsRepository.updateMediaStoreVersion(currentVersion)
            }

            // 11. Production Path: Retry any pending retryable OCR memories
            try {
                retryPendingOcrInternal()
            } catch (e: Exception) {
                CayanaLogger.w("ScreenshotCoordinator", "Failed retrying pending OCR: ${e.message}")
            }

            newMemories
        }
    }

    /**
     * Establishes high-water mark cursor to current latest MediaStore item and updates watcher status to ACTIVE.
     */
    suspend fun establishBaseline(forceNew: Boolean = false): Long = withContext(dispatchers.io) {
        val currentSettings = settingsRepository.getSettings().first()
        val currentVersion = mediaStoreVersionProvider(context)
        if (!forceNew && currentSettings.lastScreenshotMediaId > 0L && currentVersion != null && currentSettings.mediaStoreVersion == currentVersion) {
            return@withContext currentSettings.lastScreenshotMediaId
        }

        var maxId = 0L
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media._ID} DESC LIMIT 1"
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

        settingsRepository.updateLastScreenshotMediaId(maxId)
        if (currentVersion != null) {
            settingsRepository.updateMediaStoreVersion(currentVersion)
        }
        settingsRepository.updateScreenshotWatcherStatus(com.cayana.ui.settings.repository.ScreenshotWatcherStatus.ACTIVE)
        maxId
    }

    /**
     * Initializes high-water mark cursor to current latest MediaStore item.
     */
    suspend fun initializeCursorToLatest(): Long = establishBaseline(forceNew = true)

    /**
     * Retries OCR processing for memories that experienced transient failures.
     */
    suspend fun retryPendingOcr(): Int = withContext(dispatchers.io) {
        processingMutex.withLock {
            retryPendingOcrInternal()
        }
    }

    private suspend fun retryPendingOcrInternal(): Int {
        val allMemories = memoryRepository.getAllMemories().first()
        val retryableItems = allMemories
            .filter { it.processingState == ProcessingState.FAILED_RETRYABLE }
            .take(MAX_OCR_RETRIES_PER_CYCLE)
        var retriedCount = 0

        for (item in retryableItems) {
            val uriString = item.sourceUri
            if (uriString.isNullOrBlank()) {
                val updated = item.copy(
                    processingState = ProcessingState.FAILED_PERMANENT,
                    sourceExists = false
                )
                memoryRepository.saveMemory(updated)
                continue
            }

            // 1. Check if source file still exists
            val exists = try {
                val uri = android.net.Uri.parse(uriString)
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: Exception) {
                false
            }

            if (!exists) {
                CayanaLogger.i("ScreenshotCoordinator", "Source file no longer exists for ${item.id}, marking FAILED_PERMANENT")
                val updated = item.copy(
                    processingState = ProcessingState.FAILED_PERMANENT,
                    sourceExists = false
                )
                memoryRepository.saveMemory(updated)
                continue
            }

            // 2. Check retry count (max 5)
            val currentRetryCount = item.metadata["ocrRetryCount"]?.toIntOrNull() ?: 0
            val nextRetryCount = currentRetryCount + 1

            if (nextRetryCount > MAX_OCR_RETRIES_PER_ITEM) {
                CayanaLogger.w("ScreenshotCoordinator", "Max OCR retry count exceeded for ${item.id}, marking FAILED_PERMANENT")
                val updatedMetadata = item.metadata.toMutableMap().apply {
                    put("ocrRetryCount", nextRetryCount.toString())
                }
                val updated = item.copy(
                    processingState = ProcessingState.FAILED_PERMANENT,
                    metadata = updatedMetadata
                )
                memoryRepository.saveMemory(updated)
                continue
            }

            // 3. Attempt OCR
            try {
                when (val ocrResult = ocrEngine.processImage(uriString)) {
                    is Result.Success -> {
                        val text = ocrResult.data.fullText.trim()
                        val (newText, newState) = if (text.isNotBlank()) {
                            Pair(text, ProcessingState.COMPLETED)
                        } else {
                            Pair(null, ProcessingState.COMPLETED_WITHOUT_TEXT)
                        }
                        val newTitle = if (item.title == "螢幕截圖" || item.title == item.metadata["displayName"]) {
                            deriveTitle(newText, item.metadata["displayName"])
                        } else {
                            item.title
                        }
                        val updatedMetadata = item.metadata.toMutableMap().apply {
                            put("ocrRetryCount", nextRetryCount.toString())
                        }
                        val updated = item.copy(
                            rawText = newText,
                            normalizedText = newText,
                            title = newTitle,
                            metadata = updatedMetadata,
                            processingState = newState
                        )
                        memoryRepository.saveMemory(updated)
                        retriedCount++
                    }
                    is Result.Error, Result.Loading -> {
                        val updatedState = if (nextRetryCount >= 5) ProcessingState.FAILED_PERMANENT else ProcessingState.FAILED_RETRYABLE
                        val updatedMetadata = item.metadata.toMutableMap().apply {
                            put("ocrRetryCount", nextRetryCount.toString())
                        }
                        val updated = item.copy(
                            metadata = updatedMetadata,
                            processingState = updatedState
                        )
                        memoryRepository.saveMemory(updated)
                    }
                }
            } catch (e: Exception) {
                val updatedState = if (nextRetryCount >= 5) ProcessingState.FAILED_PERMANENT else ProcessingState.FAILED_RETRYABLE
                val updatedMetadata = item.metadata.toMutableMap().apply {
                    put("ocrRetryCount", nextRetryCount.toString())
                }
                val updated = item.copy(
                    metadata = updatedMetadata,
                    processingState = updatedState
                )
                memoryRepository.saveMemory(updated)
            }
        }
        return retriedCount
    }

    private fun deriveTitle(rawText: String?, displayName: String?): String {
        if (!rawText.isNullOrBlank()) {
            val firstLine = rawText.lines().firstOrNull { it.isNotBlank() }?.trim()
            if (!firstLine.isNullOrBlank()) {
                return if (firstLine.length > 40) {
                    firstLine.take(37) + "..."
                } else {
                    firstLine
                }
            }
        }
        return displayName?.substringBeforeLast(".") ?: "螢幕截圖"
    }

    companion object {
        const val MAX_OCR_RETRIES_PER_CYCLE = 5
        const val MAX_OCR_RETRIES_PER_ITEM = 5

        fun getMediaStoreVersion(context: Context): String? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val version = MediaStore.getVersion(context)
                    if (version.isNullOrBlank()) null else version
                } catch (e: Exception) {
                    CayanaLogger.w("ScreenshotCoordinator", "Failed to get MediaStore version: ${e.message}")
                    null
                }
            } else {
                "pre_q"
            }
        }
    }
}
