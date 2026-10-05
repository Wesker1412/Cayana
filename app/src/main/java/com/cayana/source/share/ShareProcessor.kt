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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val ocrEngine: OcrEngine
) {

    // Dedup cache: fingerprint -> (timestamp, memoryIds)
    private val recentShares = ConcurrentHashMap<String, Pair<Long, List<String>>>()

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

        // Check Dedup
        val fingerprint = computeFingerprint(action, rawMimeType, textExtra, streamUris)
        val now = System.currentTimeMillis()
        val cached = recentShares[fingerprint]
        if (cached != null && (now - cached.first) < ShareIngestConfig.DEDUP_WINDOW_MS) {
            CayanaLogger.i("ShareProcessor", "Duplicate share intent detected within dedup window")
            return@withContext ShareIngestResult.Duplicate(cached.second)
        }

        val savedMemories = mutableListOf<MemoryItem>()
        var hasUnreadableContent = false

        // 1. Process Text / URL
        if (!textExtra.isNullOrBlank()) {
            val memory = processTextOrUrl(textExtra, rawMimeType)
            memoryRepository.saveMemory(memory)
            savedMemories.add(memory)
            CayanaLogger.i("ShareProcessor", "Saved share text memory: id=${memory.id}, type=${memory.sourceType}")
        }

        // 2. Process Streams (bounded by MAX_ITEM_COUNT)
        val boundedUris = streamUris.take(ShareIngestConfig.MAX_ITEM_COUNT)
        for (uri in boundedUris) {
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

            val resolvedMime = context.contentResolver.getType(uri)
            val mime = if (resolvedMime != null && !resolvedMime.startsWith("vnd.android.cursor")) {
                resolvedMime
            } else {
                rawMimeType
            }

            when {
                mime.startsWith("image/") -> {
                    val result = processImageUri(uri, mime)
                    if (result != null) {
                        savedMemories.add(result)
                    } else {
                        hasUnreadableContent = true
                    }
                }
                mime == "application/pdf" || mime.contains("pdf") -> {
                    val result = processPdfUri(uri, mime)
                    if (result != null) {
                        savedMemories.add(result)
                    } else {
                        hasUnreadableContent = true
                    }
                }
                else -> {
                    // Other document types
                    val result = processGenericDocumentUri(uri, mime)
                    if (result != null) {
                        savedMemories.add(result)
                    } else {
                        hasUnreadableContent = true
                    }
                }
            }
        }

        if (savedMemories.isEmpty()) {
            return@withContext ShareIngestResult.Ignored("No valid memories created")
        }

        val memoryIds = savedMemories.map { it.id }
        recentShares[fingerprint] = Pair(now, memoryIds)

        return@withContext if (hasUnreadableContent) {
            ShareIngestResult.PartialSuccess(savedMemories)
        } else {
            ShareIngestResult.Success(savedMemories)
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

    private fun processTextOrUrl(text: String, originalMime: String): MemoryItem {
        val extractedUrl = extractUrl(text)
        val now = System.currentTimeMillis()

        return if (extractedUrl != null) {
            val canonicalUrl = normalizeUrl(extractedUrl)
            val host = MemorySearchDocumentBuilder.extractHost(canonicalUrl)
            val hostLabel = MemorySearchDocumentBuilder.getDeterministicHostLabel(host)
            val displayTitle = hostLabel ?: host ?: "Shared Link"

            MemoryItem(
                id = UUID.randomUUID().toString(),
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
                id = UUID.randomUUID().toString(),
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

    private suspend fun processImageUri(uri: Uri, mime: String): MemoryItem? {
        val now = System.currentTimeMillis()
        val displayName = queryDisplayName(uri) ?: "Shared Image"
        val memoryId = UUID.randomUUID().toString()

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
            }
        } catch (e: Exception) {
            CayanaLogger.w("ShareProcessor", "Failed to copy image stream: ${e.message}")
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

        if (!copySuccess || !tempFile.exists()) {
            tempFile.delete()
            memory = memory.copy(
                processingState = ProcessingState.FAILED_PERMANENT,
                metadata = memory.metadata + ("error" to "STREAM_READ_FAILED")
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
                        metadata = memory.metadata + ("error" to (ocrResult.exception.message ?: "OCR_ERROR"))
                    )
                }
                else -> memory
            }
            memoryRepository.saveMemory(memory)
        } catch (e: Exception) {
            CayanaLogger.w("ShareProcessor", "OCR failed on shared image: ${e.message}")
            memory = memory.copy(
                processingState = ProcessingState.FAILED_RETRYABLE,
                metadata = memory.metadata + ("error" to (e.message ?: "OCR_EXCEPTION"))
            )
            memoryRepository.saveMemory(memory)
        } finally {
            // Delete temp processing file
            tempFile.delete()
        }

        return memory
    }

    private suspend fun processPdfUri(uri: Uri, mime: String): MemoryItem? {
        val now = System.currentTimeMillis()
        val displayName = queryDisplayName(uri) ?: "Shared Document.pdf"
        val size = queryFileSize(uri)

        val memory = MemoryItem(
            id = UUID.randomUUID().toString(),
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

    private suspend fun processGenericDocumentUri(uri: Uri, mime: String): MemoryItem? {
        val now = System.currentTimeMillis()
        val displayName = queryDisplayName(uri) ?: "Shared Document"
        val size = queryFileSize(uri)

        val memory = MemoryItem(
            id = UUID.randomUUID().toString(),
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
}
