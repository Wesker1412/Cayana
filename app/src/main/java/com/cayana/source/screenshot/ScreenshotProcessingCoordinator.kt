package com.cayana.source.screenshot

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
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
    private val dispatchers: CoroutineDispatchers = AppDispatchers()
) {
    private val processingMutex = Mutex()

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

            val lastProcessedId = settings.lastScreenshotMediaId
            val newMemories = mutableListOf<MemoryItem>()
            var maxEncounteredId = lastProcessedId

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
                        if (mediaId > maxEncounteredId) {
                            maxEncounteredId = mediaId
                        }

                        val displayName = cursor.getString(nameCol)
                        val relativePath = cursor.getString(pathCol)
                        val bucketName = cursor.getString(bucketCol)
                        val mimeType = cursor.getString(mimeCol)
                        val dateAddedSec = cursor.getLong(dateAddedCol)
                        val dateTakenMs = cursor.getLong(dateTakenCol)
                        val size = cursor.getLong(sizeCol)

                        // Calculate capturedAt timestamp
                        val capturedAt = if (dateTakenMs > 0) {
                            dateTakenMs
                        } else if (dateAddedSec > 0) {
                            dateAddedSec * 1000L
                        } else {
                            System.currentTimeMillis()
                        }

                        // 2. Screenshot Classification
                        if (!ScreenshotClassifier.isScreenshot(relativePath, displayName, bucketName, mimeType)) {
                            continue
                        }

                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            mediaId
                        )
                        val sourceUriString = contentUri.toString()

                        // 3. Deduplication Check
                        val existing = memoryRepository.getMemoryBySourceUri(sourceUriString)
                        if (existing != null) {
                            CayanaLogger.d("ScreenshotCoordinator", "Item already ingested for uri: ${PrivacySanitizer.sanitizeUri(sourceUriString)}")
                            continue
                        }

                        // 4. Local OCR Processing
                        val ocrResult = ocrEngine.processImage(sourceUriString)
                        val (rawText, processingState) = when (ocrResult) {
                            is Result.Success -> {
                                val text = ocrResult.data.fullText.trim()
                                if (text.isNotBlank()) {
                                    Pair(text, ProcessingState.COMPLETED)
                                } else {
                                    Pair(null, ProcessingState.COMPLETED_WITHOUT_TEXT)
                                }
                            }
                            is Result.Error, Result.Loading -> {
                                Pair(null, ProcessingState.COMPLETED_WITHOUT_TEXT)
                            }
                        }

                        val title = deriveTitle(rawText, displayName)

                        // 5. Build Unified MemoryItem with deterministic UUID
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

                        // 6. Persist to Room
                        memoryRepository.saveMemory(memoryItem)
                        newMemories.add(memoryItem)
                        CayanaLogger.i("ScreenshotCoordinator", "Successfully ingested screenshot memory: $deterministicId")

                        // 7. Notification
                        NotificationHelper.showMemoryIngestedNotification(context, memoryItem)
                    }
                }
            } catch (e: Exception) {
                CayanaLogger.w("ScreenshotCoordinator", "Error reading MediaStore: ${e.javaClass.simpleName}")
            } finally {
                cursor?.close()
            }

            // 8. Advance High-Water Mark Cursor
            if (maxEncounteredId > lastProcessedId) {
                settingsRepository.updateLastScreenshotMediaId(maxEncounteredId)
            }

            newMemories
        }
    }

    /**
     * Initializes high-water mark cursor to current latest MediaStore item if not already set.
     */
    suspend fun initializeCursorToLatest(): Long = withContext(dispatchers.io) {
        val currentSettings = settingsRepository.getSettings().first()
        if (currentSettings.lastScreenshotMediaId > 0L) {
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

        if (maxId > 0L) {
            settingsRepository.updateLastScreenshotMediaId(maxId)
        }
        maxId
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
}
