package com.cayana.core.logging

/**
 * Enforces Cayana Privacy Principles:
 * 1. Do not record raw OCR or Transcripts into general logs.
 * 2. Crash reports must never contain raw Memory contents.
 * 3. Analytics must never contain raw Memory contents.
 */
object PrivacySanitizer {

    /**
     * Sanitizes memory text content before logging.
     * Replaces actual textual content with safe metadata (e.g. character count).
     */
    fun sanitizeMemoryText(rawText: String?): String {
        if (rawText.isNullOrEmpty()) return "[EMPTY]"
        return "[REDACTED_MEMORY_LEN_${rawText.length}]"
    }

    /**
     * Sanitizes file paths or URIs to avoid leaking user identifiable local directory structures.
     */
    fun sanitizeUri(uri: String?): String {
        if (uri.isNullOrEmpty()) return "[EMPTY_URI]"
        val lastSegment = uri.substringAfterLast('/').substringAfterLast('\\')
        return ".../$lastSegment"
    }

    /**
     * Safety net: detects accidental logging of memory objects (e.g. MemoryItem toString()
     * containing rawText, transcript, or normalizedText fields) and redacts the private payload.
     */
    fun guardAgainstMemoryLeakage(message: String): String {
        return if (message.contains("rawText=", ignoreCase = true) ||
            message.contains("transcript=", ignoreCase = true) ||
            message.contains("normalizedText=", ignoreCase = true)
        ) {
            message.replace(Regex("(rawText|transcript|normalizedText)=[^,)\\]]+"), "$1=[REDACTED_BY_PRIVACY_GUARD]")
        } else {
            message
        }
    }
}
