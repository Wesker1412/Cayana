package com.cayana.source.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.processing.OcrEngine
import com.cayana.processing.ProcessingState
import com.cayana.search.MemorySearchDocumentBuilder
import com.cayana.source.SourceType
import com.cayana.source.share.data.ShareReceiptDao
import com.cayana.source.share.data.ShareReceiptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ShareProcessor(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val ocrEngine: OcrEngine,
    private val shareReceiptDao: ShareReceiptDao? = null
) {

    // In-process serialization per fingerprint with ref-counted cleanup
    private class LockHolder(
        val mutex: Mutex = Mutex(),
        var refCount: Int = 1
    )
    private val fingerprintLocks = HashMap<String, LockHolder>()
    private val lockMonitor = Any()
    // Fallback RAM cache when shareReceiptDao is null (e.g. tests without DB)
    private val ramShares = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    suspend fun processIntent(intent: Intent): ShareIngestResult = withContext(Dispatchers.IO) {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            CayanaLogger.i("ShareProcessor", "Ignoring intent with unsupported action: $action")
            return@withContext ShareIngestResult.Ignored("Unsupported action: $action")
        }

        val rawMimeType = intent.type ?: ""
        if (!isMimeSupported(rawMimeType)) {
            CayanaLogger.i("ShareProcessor", "Ignoring intent with unsupported MIME: $rawMimeType")
            return@withContext ShareIngestResult.Ignored("Unsupported MIME: $rawMimeType")
        }

        val textExtra = intent.getStringExtra(Intent.EXTRA_TEXT)
        val streamUris = extractStreamUris(intent)

        if (textExtra.isNullOrBlank() && streamUris.isEmpty()) {
            CayanaLogger.i("ShareProcessor", "No readable content found in share Intent")
            return@withContext ShareIngestResult.Ignored("Empty content")
        }

        // Deduplication & atomic claim per fingerprint
        val fingerprint = computeFingerprint(action, rawMimeType, textExtra, streamUris)
        val holder = synchronized(lockMonitor) {
            val existing = fingerprintLocks[fingerprint]
            if (existing != null) {
                existing.refCount++
                existing
            } else {
                val created = LockHolder()
                fingerprintLocks[fingerprint] = created
                created
            }
        }

        try {
            return@withContext holder.mutex.withLock {
                val now = System.currentTimeMillis()
                val sessionId: String

                if (shareReceiptDao != null) {
                    shareReceiptDao.deleteExpired(now)
                    val existing = shareReceiptDao.getReceipt(fingerprint)
                    if (existing != null) {
                        if (existing.status == "COMPLETED" && now < existing.expiresAt) {
                            CayanaLogger.i("ShareProcessor", "Duplicate share intent detected within dedup window")
                            val cachedIds = parseItemIdsJson(existing.itemIdsJson)
                            return@withLock ShareIngestResult.Duplicate(cachedIds)
                        } else if (existing.status == "PROCESSING") {
                            // In-flight or process-death recovery: resume same session (never expires prematurely)
                            CayanaLogger.i("ShareProcessor", "Resuming in-flight share session")
                            sessionId = existing.sessionId
                        } else if (existing.status == "IGNORED" && now < existing.expiresAt) {
                            CayanaLogger.i("ShareProcessor", "Ignored share intent within window")
                            return@withLock ShareIngestResult.Ignored("Empty content")
                        } else {
                            // Expired or replaced session
                            sessionId = UUID.randomUUID().toString()
                            val newReceipt = ShareReceiptEntity(
                                fingerprint = fingerprint,
                                sessionId = sessionId,
                                createdAt = now,
                                expiresAt = Long.MAX_VALUE,
                                status = "PROCESSING",
                                itemIdsJson = "[]"
                            )
                            shareReceiptDao.upsertReceipt(newReceipt)
                        }
                    } else {
                        sessionId = UUID.randomUUID().toString()
                        val newReceipt = ShareReceiptEntity(
                            fingerprint = fingerprint,
                            sessionId = sessionId,
                            createdAt = now,
                            expiresAt = Long.MAX_VALUE,
                            status = "PROCESSING",
                            itemIdsJson = "[]"
                        )
                        shareReceiptDao.upsertReceipt(newReceipt)
                    }
                } else {
                    val cached = ramShares[fingerprint]
                    if (cached != null && (now - cached.first) < ShareIngestConfig.DEDUP_WINDOW_MS) {
                        if (cached.second.isEmpty()) {
                            return@withLock ShareIngestResult.Ignored("Empty content")
                        } else {
                            CayanaLogger.i("ShareProcessor", "Duplicate share intent detected within dedup window")
                            return@withLock ShareIngestResult.Duplicate(cached.second)
                        }
                    }
                    sessionId = UUID.randomUUID().toString()
                }

                val savedMemories = mutableListOf<MemoryItem>()
                var hasUnreadableContent = false

                // 1. Process Text / URL
                if (!textExtra.isNullOrBlank()) {
                    val memory = processTextOrUrl(textExtra, rawMimeType, sessionId)
                    memoryRepository.saveMemory(memory)
                    savedMemories.add(memory)
                    CayanaLogger.i("ShareProcessor", "Saved share text memory: id=${memory.id}, type=${memory.sourceType}")
                }

                // 2. Process Streams (bounded by MAX_ITEM_COUNT)
                val boundedUris = streamUris.take(ShareIngestConfig.MAX_ITEM_COUNT)
                for ((index, uri) in boundedUris.withIndex()) {
                    // Validate scheme: reject file:// and unsupported schemes
                    val scheme = uri.scheme?.lowercase(Locale.ROOT)
                    if (scheme != "content" && scheme != "android.resource") {
                        CayanaLogger.w("ShareProcessor", "Rejecting URI with unsafe scheme: $scheme")
                        hasUnreadableContent = true
                        continue
                    }

                    // Try persisting permission if allowed
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {}

                    val resolvedMime = try {
                        context.contentResolver.getType(uri)
                    } catch (_: Exception) {
                        null
                    }
                    val mime = if (resolvedMime != null && !resolvedMime.startsWith("vnd.android.cursor")) {
                        resolvedMime
                    } else {
                        rawMimeType
                    }

                    when {
                        mime.startsWith("image/") -> {
                            val result = processImageUri(uri, mime, sessionId, index)
                            if (result != null) {
                                savedMemories.add(result)
                            } else {
                                hasUnreadableContent = true
                            }
                        }
                        mime == "application/pdf" || mime.contains("pdf") -> {
                            val result = processPdfUri(uri, mime, sessionId, index)
                            if (result != null) {
                                savedMemories.add(result)
                            } else {
                                hasUnreadableContent = true
                            }
                        }
                        else -> {
                            // Other document types
                            val result = processGenericDocumentUri(uri, mime, sessionId, index)
                            if (result != null) {
                                savedMemories.add(result)
                            } else {
                                hasUnreadableContent = true
                            }
                        }
                    }
                }

                if (savedMemories.isEmpty()) {
                    val completionTime = System.currentTimeMillis()
                    if (shareReceiptDao != null) {
                        shareReceiptDao.updateStatus(
                            fingerprint = fingerprint,
                            status = "IGNORED",
                            itemIdsJson = "[]",
                            expiresAt = completionTime + ShareIngestConfig.DEDUP_WINDOW_MS
                        )
                    } else {
                        ramShares[fingerprint] = Pair(completionTime, emptyList())
                    }
                    return@withLock ShareIngestResult.Ignored("No valid memories created")
                }

                val completionTime = System.currentTimeMillis()
                val memoryIds = savedMemories.map { it.id }
                val itemIdsJson = JSONArray(memoryIds).toString()
                if (shareReceiptDao != null) {
                    shareReceiptDao.updateStatus(
                        fingerprint = fingerprint,
                        status = "COMPLETED",
                        itemIdsJson = itemIdsJson,
                        expiresAt = completionTime + ShareIngestConfig.DEDUP_WINDOW_MS
                    )
                } else {
                    ramShares[fingerprint] = Pair(completionTime, memoryIds)
                }

                return@withLock if (hasUnreadableContent) {
                    ShareIngestResult.PartialSuccess(savedMemories)
                } else {
                    ShareIngestResult.Success(savedMemories)
                }
            }
        } finally {
            synchronized(lockMonitor) {
                holder.refCount--
                if (holder.refCount <= 0) {
                    fingerprintLocks.remove(fingerprint)
                }
            }
        }
    }

    private fun isMimeSupported(mime: String): Boolean {
        if (mime.isBlank()) return false
        val lower = mime.lowercase(Locale.ROOT)
        return ShareIngestConfig.SUPPORTED_MIME_PATTERNS.any { pattern ->
            when {
                pattern.endsWith("/*") -> lower.startsWith(pattern.removeSuffix("/*") + "/")
                else -> lower == pattern
            }
        }
    }

    private fun extractStreamUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) uris.add(uri)
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (list != null) uris.addAll(list.filterNotNull())
        }
        return uris
    }

    private fun processTextOrUrl(text: String, originalMime: String, sessionId: String): MemoryItem {
        val extractedUrl = extractUrl(text)
        val now = System.currentTimeMillis()
        val memoryId = UUID.nameUUIDFromBytes("share:$sessionId:text".toByteArray(Charsets.UTF_8)).toString()

        return if (extractedUrl != null) {
            val canonicalUrl = normalizeUrl(extractedUrl)
            val host = MemorySearchDocumentBuilder.extractHost(canonicalUrl)
            val hostLabel = MemorySearchDocumentBuilder.getDeterministicHostLabel(host)
            val displayTitle = hostLabel ?: host ?: "Shared Link"

            MemoryItem(
                id = memoryId,
                sourceType = SourceType.SHARED_URL,
                createdAt = now,
                capturedAt = now,
                title = displayTitle,
                rawText = text,
                normalizedText = text.trim(),
                sourceUrl = canonicalUrl,
                sourceExists = true,
                metadata = mapOf(
                    "canonicalUrl" to canonicalUrl,
                    "host" to (host ?: ""),
                    "originalMime" to originalMime,
                    "sharedKind" to "URL"
                ),
                processingState = ProcessingState.COMPLETED
            )
        } else {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Shared Text"
            val displayTitle = if (firstLine.length > 30) firstLine.take(30) + "..." else firstLine

            MemoryItem(
                id = memoryId,
                sourceType = SourceType.SHARED_TEXT,
                createdAt = now,
                capturedAt = now,
                title = displayTitle,
                rawText = text,
                normalizedText = text.trim(),
                sourceExists = true,
                metadata = mapOf(
                    "originalMime" to originalMime,
                    "sharedKind" to "TEXT"
                ),
                processingState = ProcessingState.COMPLETED
            )
        }
    }

    private suspend fun processImageUri(uri: Uri, mime: String, sessionId: String, index: Int): MemoryItem? {
        val now = System.currentTimeMillis()
        val displayName = queryDisplayName(uri) ?: "Shared Image"
        val memoryId = UUID.nameUUIDFromBytes("share:$sessionId:stream:$index".toByteArray(Charsets.UTF_8)).toString()

        // 1. Initial durable Memory
        var memory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SHARED_IMAGE,
            createdAt = now,
            capturedAt = now,
            title = displayName,
            rawText = null,
            normalizedText = null,
            sourceUri = uri.toString(),
            sourceExists = true,
            metadata = mapOf(
                "displayName" to displayName,
                "mimeType" to mime
            ),
            processingState = ProcessingState.PENDING
        )
        memoryRepository.saveMemory(memory)

        // 2. Ephemeral copy to temp file for OCR
        val tempDir = File(context.cacheDir, "share_temp").apply { mkdirs() }
        val tempFile = File(tempDir, "share_${memoryId}.tmp")

        var isOversized = false
        var copySuccess = false
        var streamErrorType: String? = null

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var totalBytes = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            totalBytes += bytesRead
                            if (totalBytes > ShareIngestConfig.MAX_TEMP_BYTES) {
                                isOversized = true
                                break
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                copySuccess = !isOversized
            } else {
                streamErrorType = "STREAM_READ_FAILED"
            }
        } catch (e: SecurityException) {
            CayanaLogger.w("ShareProcessor", "SecurityException copying image stream: ${e.javaClass.simpleName}")
            streamErrorType = "URI_PERMISSION_LOST"
        } catch (e: Exception) {
            CayanaLogger.w("ShareProcessor", "Failed to copy image stream: ${e.javaClass.simpleName}")
            streamErrorType = "STREAM_READ_FAILED"
        }

        if (isOversized) {
            CayanaLogger.w("ShareProcessor", "Shared image stream exceeded MAX_TEMP_BYTES")
            tempFile.delete()
            memory = memory.copy(
                processingState = ProcessingState.FAILED_PERMANENT,
                metadata = memory.metadata + ("error" to "OVERSIZED_STREAM")
            )
            memoryRepository.saveMemory(memory)
            return memory
        }

        if (!copySuccess || !tempFile.exists() || streamErrorType != null) {
            tempFile.delete()
            val err = streamErrorType ?: "STREAM_READ_FAILED"
            memory = memory.copy(
                processingState = ProcessingState.FAILED_PERMANENT,
                metadata = memory.metadata + ("error" to err)
            )
            memoryRepository.saveMemory(memory)
            return memory
        }

        // 3. Run Local OCR on temp file
        try {
            val ocrResult = ocrEngine.extractText(Uri.fromFile(tempFile).toString())
            memory = when (ocrResult) {
                is com.cayana.core.common.Result.Success -> {
                    val text = ocrResult.data.trim()
                    memory.copy(
                        rawText = text.ifBlank { null },
                        normalizedText = text.ifBlank { null },
                        processingState = if (text.isBlank()) {
                            ProcessingState.COMPLETED_WITHOUT_TEXT
                        } else {
                            ProcessingState.COMPLETED
                        }
                    )
                }
                is com.cayana.core.common.Result.Error -> {
                    memory.copy(
                        processingState = ProcessingState.FAILED_RETRYABLE,
                        metadata = memory.metadata + ("error" to "OCR_FAILED")
                    )
                }
                else -> memory
            }
            memoryRepository.saveMemory(memory)
        } catch (e: Exception) {
            CayanaLogger.w("ShareProcessor", "OCR failed on shared image: ${e.javaClass.simpleName}")
            memory = memory.copy(
                processingState = ProcessingState.FAILED_RETRYABLE,
                metadata = memory.metadata + ("error" to "OCR_FAILED")
            )
            memoryRepository.saveMemory(memory)
        } finally {
            // Delete temp processing file
            tempFile.delete()
        }

        return memory
    }

    private suspend fun processPdfUri(uri: Uri, mime: String, sessionId: String, index: Int): MemoryItem? {
        val now = System.currentTimeMillis()
        val displayName = queryDisplayName(uri) ?: "Shared Document.pdf"
        val size = queryFileSize(uri)
        val memoryId = UUID.nameUUIDFromBytes("share:$sessionId:stream:$index".toByteArray(Charsets.UTF_8)).toString()

        val memory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SHARED_DOCUMENT,
            createdAt = now,
            capturedAt = now,
            title = displayName,
            rawText = null,
            normalizedText = null,
            sourceUri = uri.toString(),
            sourceExists = true,
            metadata = mapOf(
                "displayName" to displayName,
                "mimeType" to mime,
                "size" to (size?.toString() ?: "0")
            ),
            processingState = ProcessingState.COMPLETED_WITHOUT_TEXT
        )
        memoryRepository.saveMemory(memory)
        CayanaLogger.i("ShareProcessor", "Saved shared PDF document: id=${memory.id}")
        return memory
    }

    private suspend fun processGenericDocumentUri(uri: Uri, mime: String, sessionId: String, index: Int): MemoryItem? {
        val now = System.currentTimeMillis()
        val displayName = queryDisplayName(uri) ?: "Shared Document"
        val size = queryFileSize(uri)
        val memoryId = UUID.nameUUIDFromBytes("share:$sessionId:stream:$index".toByteArray(Charsets.UTF_8)).toString()

        val memory = MemoryItem(
            id = memoryId,
            sourceType = SourceType.SHARED_DOCUMENT,
            createdAt = now,
            capturedAt = now,
            title = displayName,
            rawText = null,
            normalizedText = null,
            sourceUri = uri.toString(),
            sourceExists = true,
            metadata = mapOf(
                "displayName" to displayName,
                "mimeType" to mime,
                "size" to (size?.toString() ?: "0")
            ),
            processingState = ProcessingState.COMPLETED_WITHOUT_TEXT
        )
        memoryRepository.saveMemory(memory)
        return memory
    }

    fun extractUrl(text: String): String? {
        val match = Regex("https?://[^\\s<>\"'\\[\\]()]+").find(text) ?: return null
        return match.value
    }

    fun normalizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        // Strip trailing punctuation: . , ; : ) ] }
        url = url.trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')
        // Strip leading punctuation: < ( [ " '
        url = url.trimStart('<', '(', '[', '{', '"', '\'')

        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: "https"
            val host = uri.host?.lowercase(Locale.ROOT) ?: ""
            val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            val path = uri.rawPath ?: ""
            val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
            val fragment = if (uri.rawFragment != null) "#${uri.rawFragment}" else ""
            "$scheme://$host$port$path$query$fragment"
        } catch (_: Exception) {
            url
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryFileSize(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeFingerprint(
        action: String?,
        mime: String?,
        text: String?,
        streamUris: List<Uri>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((action ?: "").toByteArray())
        digest.update((mime ?: "").toByteArray())
        digest.update((text ?: "").toByteArray())
        streamUris.forEach { digest.update(it.toString().toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseItemIdsJson(json: String): List<String> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
