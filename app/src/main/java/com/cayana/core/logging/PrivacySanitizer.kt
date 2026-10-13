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
     * Defense-in-depth safety net:
     * Callers must prioritize avoiding passing raw private memory or credentials to logger methods.
     * This sanitizer acts as a last line of defense for recognized field patterns (e.g. MemoryItem toString(),
     * common credential keys, and JSON payloads). It does not claim to detect arbitrary unstructured PII.
     *
     * Handles multiline, spaces, and punctuation in rawText/transcript/normalizedText,
     * while safely redacting credentials (recoveryKey, tokens, secrets) without truncating
     * unrelated diagnostic properties.
     */
    fun guardAgainstMemoryLeakage(message: String): String {
        if (message.isBlank()) return message

        var result = message

        // 1. Redact free-text memory content (rawText, transcript, normalizedText)
        // Matches across spaces, punctuation, and newlines until the next field assignment (e.g. ", title="),
        // closing delimiter (")", "]", "}"), or end-of-string.
        if (result.contains("rawText=", ignoreCase = true) ||
            result.contains("transcript=", ignoreCase = true) ||
            result.contains("normalizedText=", ignoreCase = true)
        ) {
            val memoryFieldRegex = Regex(
                "(?i)(rawText|transcript|normalizedText)=([\\s\\S]*?)(?=(,\\s*[a-zA-Z_][a-zA-Z0-9_]*=|[)\\]}]|$))"
            )
            result = memoryFieldRegex.replace(result) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                "$fieldName=[REDACTED_BY_PRIVACY_GUARD]"
            }
        }

        // 2. Redact credentials, tokens, recovery keys, and verifiers
        // Matches tokens/secrets until the next field assignment, comma, closing delimiter, or whitespace boundary before another key
        if (result.contains("recoveryKey=", ignoreCase = true) ||
            result.contains("accessToken=", ignoreCase = true) ||
            result.contains("refreshToken=", ignoreCase = true) ||
            result.contains("codeVerifier=", ignoreCase = true) ||
            result.contains("secret=", ignoreCase = true) ||
            result.contains("client_secret=", ignoreCase = true) ||
            result.contains("clientSecret=", ignoreCase = true)
        ) {
            val credentialFieldRegex = Regex(
                "(?i)(recoveryKey|accessToken|refreshToken|codeVerifier|secret|client_secret|clientSecret)=([\\s\\S]*?)(?=(,\\s*[a-zA-Z_][a-zA-Z0-9_]*=|\\s+[a-zA-Z_][a-zA-Z0-9_]*=|[)\\]}]|\\s*$))"
            )
            result = credentialFieldRegex.replace(result) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                "$fieldName=[REDACTED_BY_PRIVACY_GUARD]"
            }
        }

        // 3. JSON formatted fields: "rawText": "...", "accessToken": "...", etc.
        val jsonSensitiveFieldRegex = Regex(
            "\"(rawText|transcript|normalizedText|recoveryKey|accessToken|refreshToken|codeVerifier|secret|client_secret|clientSecret)\"\\s*:\\s*\"[^\"]*\"",
            RegexOption.IGNORE_CASE
        )
        if (result.contains("\"rawText\"", ignoreCase = true) ||
            result.contains("\"transcript\"", ignoreCase = true) ||
            result.contains("\"normalizedText\"", ignoreCase = true) ||
            result.contains("\"recoveryKey\"", ignoreCase = true) ||
            result.contains("\"accessToken\"", ignoreCase = true) ||
            result.contains("\"refreshToken\"", ignoreCase = true) ||
            result.contains("\"codeVerifier\"", ignoreCase = true) ||
            result.contains("\"secret\"", ignoreCase = true)
        ) {
            result = jsonSensitiveFieldRegex.replace(result, "\"$1\": \"[REDACTED_BY_PRIVACY_GUARD]\"")
        }

        // 4. HTTP Authorization Bearer tokens
        if (result.contains("Bearer ", ignoreCase = true)) {
            result = result.replace(
                Regex("Bearer\\s+[a-zA-Z0-9._~+/-]+", RegexOption.IGNORE_CASE),
                "Bearer [REDACTED_BY_PRIVACY_GUARD]"
            )
        }

        return result
    }

    /**
     * Sanitizes exception messages to prevent token, memory, or credential leakage in stack traces.
     */
    fun sanitizeThrowableMessage(message: String?): String {
        if (message.isNullOrBlank()) return "[NO_MESSAGE]"
        var result = guardAgainstMemoryLeakage(message)

        // Redact URL query parameters that might carry codes or tokens (e.g. ?code=... or &state=...)
        result = result.replace(
            Regex("([?&](code|token|access_token|refresh_token|client_secret|secret|state|key)=)[^&\\s]+", RegexOption.IGNORE_CASE),
            "$1[REDACTED_BY_PRIVACY_GUARD]"
        )

        return result
    }

    /**
     * Safely wraps or clones a Throwable so its message and cause chain do not leak raw private data,
     * while retaining the original exception class name and stack trace for diagnostics.
     */
    fun sanitizeThrowable(throwable: Throwable?): Throwable? {
        if (throwable == null) return null
        if (throwable is SanitizedDiagnosticThrowable) return throwable

        val safeMessage = sanitizeThrowableMessage(throwable.message)
        val safeCause = sanitizeThrowable(throwable.cause)
        val sanitized = SanitizedDiagnosticThrowable(
            originalClassName = throwable.javaClass.name,
            sanitizedMessage = safeMessage,
            cause = safeCause
        )
        sanitized.stackTrace = throwable.stackTrace
        return sanitized
    }
}

/**
 * Diagnostic wrapper that preserves stack trace and original exception class without leaking private content.
 */
class SanitizedDiagnosticThrowable(
    val originalClassName: String,
    val sanitizedMessage: String,
    cause: Throwable?
) : Throwable("$originalClassName: $sanitizedMessage", cause)
