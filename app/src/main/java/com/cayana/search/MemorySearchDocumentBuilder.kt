package com.cayana.search

import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.data.MemoryFtsEntity
import com.cayana.memory.model.MemoryItem
import com.cayana.source.SourceType
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MemorySearchDocumentBuilder {

    fun buildDocument(item: MemoryItem): MemoryFtsEntity {
        val host = extractHost(item.sourceUrl)
        val displayName = item.metadata["displayName"] ?: item.metadata["filename"]
        val searchTokens = buildSearchTokens(
            title = item.title,
            rawText = item.rawText,
            normalizedText = item.normalizedText,
            sourceType = item.sourceType,
            sourceUrl = item.sourceUrl,
            host = host,
            displayName = displayName,
            capturedAt = item.capturedAt,
            metadata = item.metadata
        )
        return MemoryFtsEntity(
            memoryId = item.id,
            title = item.title ?: "",
            rawText = item.rawText ?: "",
            normalizedText = item.normalizedText ?: "",
            sourceType = item.sourceType.name,
            sourceUrl = item.sourceUrl ?: "",
            host = host ?: "",
            displayName = displayName ?: "",
            searchTokens = searchTokens
        )
    }

    fun buildDocument(entity: MemoryEntity, metadata: Map<String, String>): MemoryFtsEntity {
        val sourceType = runCatching { SourceType.valueOf(entity.sourceType) }.getOrDefault(SourceType.SCREENSHOT)
        val host = extractHost(entity.sourceUrl)
        val displayName = metadata["displayName"] ?: metadata["filename"]
        val searchTokens = buildSearchTokens(
            title = entity.title,
            rawText = entity.rawText,
            normalizedText = entity.normalizedText,
            sourceType = sourceType,
            sourceUrl = entity.sourceUrl,
            host = host,
            displayName = displayName,
            capturedAt = entity.capturedAt,
            metadata = metadata
        )
        return MemoryFtsEntity(
            memoryId = entity.id,
            title = entity.title ?: "",
            rawText = entity.rawText ?: "",
            normalizedText = entity.normalizedText ?: "",
            sourceType = entity.sourceType,
            sourceUrl = entity.sourceUrl ?: "",
            host = host ?: "",
            displayName = displayName ?: "",
            searchTokens = searchTokens
        )
    }

    fun buildSearchTokens(
        title: String?,
        rawText: String?,
        normalizedText: String?,
        sourceType: SourceType,
        sourceUrl: String?,
        host: String?,
        displayName: String?,
        capturedAt: Long,
        metadata: Map<String, String>
    ): String {
        val tokens = LinkedHashSet<String>()

        // 1. Source type names
        tokens.add(sourceType.name.lowercase(Locale.ROOT))
        tokens.add(sourceType.displayName.lowercase(Locale.ROOT))

        // 2. Date representations
        val date = Date(capturedAt)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date)
        val shortFormat = SimpleDateFormat("MM/dd", Locale.ROOT).format(date)
        val year = SimpleDateFormat("yyyy", Locale.ROOT).format(date)
        tokens.add(isoFormat)
        tokens.add(shortFormat)
        tokens.add(year)

        // 3. URL and host tokens
        if (!sourceUrl.isNullOrBlank()) {
            tokenizeLatinAndCjk(sourceUrl, tokens)
        }
        if (!host.isNullOrBlank()) {
            tokens.add(host.lowercase(Locale.ROOT))
            host.split('.').filter { it.isNotBlank() }.forEach {
                tokens.add(it.lowercase(Locale.ROOT))
            }
            getDeterministicHostLabel(host)?.let { label ->
                tokens.add(label.lowercase(Locale.ROOT))
            }
        }

        // 4. Display name
        if (!displayName.isNullOrBlank()) {
            tokenizeLatinAndCjk(displayName, tokens)
        }

        // 5. Selected useful metadata (appPackage, label, mimeType, filename, author, location)
        val usefulKeys = setOf("appPackage", "label", "mimeType", "filename", "author", "location")
        for ((key, value) in metadata) {
            if (key in usefulKeys && value.isNotBlank()) {
                tokenizeLatinAndCjk(value, tokens)
            }
        }

        // 6. Title, rawText, normalizedText
        if (!title.isNullOrBlank()) {
            tokenizeLatinAndCjk(title, tokens)
        }
        if (!rawText.isNullOrBlank()) {
            tokenizeLatinAndCjk(rawText, tokens)
        }
        if (!normalizedText.isNullOrBlank() && normalizedText != rawText) {
            tokenizeLatinAndCjk(normalizedText, tokens)
        }

        return tokens.joinToString(" ")
    }

    fun tokenizeLatinAndCjk(text: String, outTokens: MutableSet<String>) {
        if (text.isBlank()) return

        // Extract alphanumeric words
        val alphanumericRegex = Regex("[a-zA-Z0-9]+")
        alphanumericRegex.findAll(text).forEach { match ->
            outTokens.add(match.value.lowercase(Locale.ROOT))
        }

        // Extract CJK sequences and generate unigrams + bigrams
        val cjkBuffer = StringBuilder()
        for (ch in text) {
            if (isCjk(ch)) {
                cjkBuffer.append(ch)
            } else {
                if (cjkBuffer.isNotEmpty()) {
                    addCjkNgrams(cjkBuffer.toString(), outTokens)
                    cjkBuffer.clear()
                }
            }
        }
        if (cjkBuffer.isNotEmpty()) {
            addCjkNgrams(cjkBuffer.toString(), outTokens)
        }
    }

    private fun addCjkNgrams(cjkSequence: String, outTokens: MutableSet<String>) {
        val len = cjkSequence.length
        // Unigrams
        for (i in 0 until len) {
            outTokens.add(cjkSequence.substring(i, i + 1))
        }
        // Bigrams
        for (i in 0 until len - 1) {
            outTokens.add(cjkSequence.substring(i, i + 2))
        }
        // Full sequence if 3 to 4 characters
        if (len in 3..4) {
            outTokens.add(cjkSequence)
        }
    }

    fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = URI(url.trim())
            uri.host?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            Regex("https?://([^/:]+)").find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        }
    }

    fun getDeterministicHostLabel(host: String?): String? {
        if (host == null) return null
        val lower = host.lowercase(Locale.ROOT)
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("github.com") -> "GitHub"
            lower.contains("maps.google") || lower.contains("goo.gl/maps") -> "Google Maps"
            lower.contains("google.com") -> "Google"
            lower.contains("instagram.com") -> "Instagram"
            lower.contains("twitter.com") || lower.contains("x.com") -> "X"
            lower.contains("facebook.com") -> "Facebook"
            else -> null
        }
    }
}
