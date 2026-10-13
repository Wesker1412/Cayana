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
     * FAIL-CLOSED GUARANTEE:
     * Whenever a sensitive memory field (rawText, transcript, normalizedText) is encountered, if its boundary
     * is ambiguous (e.g. containing parentheses, brackets, quotes, commas, simulated field markers, or newlines),
     * it immediately fails closed and redacts everything from the sensitive field onwards.
     */
    fun guardAgainstMemoryLeakage(message: String): String {
        if (message.isBlank()) return message

        var result = message

        // 1. Fail-Closed Redaction for free-text memory content (rawText, transcript, normalizedText)
        // User text frequently contains parentheses like "(confidential)", brackets "[private]",
        // commas, and newlines. To prevent ANY partial tail leakage, when a free-text memory field
        // is detected, we fail-closed and redact everything from the field onwards.
        if (result.contains("rawText=", ignoreCase = true) ||
            result.contains("transcript=", ignoreCase = true) ||
            result.contains("normalizedText=", ignoreCase = true)
        ) {
            val memoryFieldFailClosedRegex = Regex(
                "(?i)(rawText|transcript|normalizedText)=[\\s\\S]*"
            )
            result = memoryFieldFailClosedRegex.replace(result) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                if (message.trimEnd().endsWith(")")) {
                    "$fieldName=[REDACTED_BY_PRIVACY_GUARD])"
                } else {
                    "$fieldName=[REDACTED_BY_PRIVACY_GUARD]"
                }
            }
        }

        // 2. Redact credentials, tokens, recovery keys, and verifiers
        if (result.contains("recoveryKey=", ignoreCase = true) ||
            result.contains("accessToken=", ignoreCase = true) ||
            result.contains("refreshToken=", ignoreCase = true) ||
            result.contains("codeVerifier=", ignoreCase = true) ||
            result.contains("secret=", ignoreCase = true) ||
            result.contains("client_secret=", ignoreCase = true) ||
            result.contains("clientSecret=", ignoreCase = true)
        ) {
            val credentialFieldRegex = Regex(
                "(?i)(recoveryKey|accessToken|refreshToken|codeVerifier|secret|client_secret|clientSecret)=[^,)\\]\\s]+"
            )
            result = credentialFieldRegex.replace(result) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                "$fieldName=[REDACTED_BY_PRIVACY_GUARD]"
            }
        }

        // 3. JSON formatted sensitive fields with support for escaped quotes (\"...)
        val jsonSensitiveFieldRegex = Regex(
            "\"(rawText|transcript|normalizedText|recoveryKey|accessToken|refreshToken|codeVerifier|secret|client_secret|clientSecret)\"\\s*:\\s*\"([^\"\\\\]|\\\\.)*\"",
            RegexOption.IGNORE_CASE
        )
        if (result.contains("\"rawText\"", ignoreCase = true) ||
            result.contains("\"transcript\"", ignoreCase = true) ||
            result.contains("\"normalizedText\"", ignoreCase = true) ||
            result.contains("\"recoveryKey\"", ignoreCase = true) ||
            result.contains("\"accessToken\"", ignoreCase = true) ||
            result.contains("\"refreshToken\"", ignoreCase = true) ||
            result.contains("\"codeVerifier\"", ignoreCase = true) ||
            result.contains("\"secret\"", ignoreCase = true) ||
            result.contains("\"client_secret\"", ignoreCase = true) ||
            result.contains("\"clientSecret\"", ignoreCase = true)
        ) {
            result = jsonSensitiveFieldRegex.replace(result, "\"$1\": \"[REDACTED_BY_PRIVACY_GUARD]\"")

            // Fail-closed fallback: if any sensitive key remains unparsed (e.g. malformed or unclosed quote),
            // redact the rest of the JSON payload
            val unredactedJsonRegex = Regex(
                "\"(rawText|transcript|normalizedText|recoveryKey|accessToken|refreshToken|codeVerifier|secret|client_secret|clientSecret)\"\\s*:\\s*\"(?!\\[REDACTED_BY_PRIVACY_GUARD\\])[\\s\\S]*",
                RegexOption.IGNORE_CASE
            )
            if (unredactedJsonRegex.containsMatchIn(result)) {
                result = unredactedJsonRegex.replace(result, "\"$1\": \"[REDACTED_BY_PRIVACY_GUARD]\"}")
            }
        }

        // 4. HTTP Authorization Bearer tokens
        if (result.contains("Bearer ", ignoreCase = true)) {
            result = result.replace(
                Regex("Bearer\\s+[^\\s,)\\]}]+", RegexOption.IGNORE_CASE),
                "Bearer [REDACTED_BY_PRIVACY_GUARD]"
            )
        }

        return result
    }

    private val SAFE_EXCEPTION_PHRASES = setOf(
        "timeout",
        "connect timed out",
        "read timed out",
        "connection timed out",
        "connection refused",
        "stream closed",
        "socket closed",
        "broken pipe",
        "network is unreachable",
        "unexpected end of stream",
        "end of input",
        "ssl handshake aborted",
        "certificate expired",
        "bad certificate",
        "permission denied",
        "file not found",
        "no such file or directory",
        "disk full",
        "database locked",
        "table locked",
        "null"
    )

    private val HTTP_STATUS_REGEX = Regex("(?i)\\bHTTP\\s*\\d{3}\\b")
    private val STRUCTURED_ERROR_CODE_REGEX = Regex("^[A-Z0-9_]{3,32}$")

    /**
     * Sanitizes exception messages:
     * Never trusts raw exception messages as safe text.
     * Retains ONLY safe whitelisted phrases, HTTP status codes, or structured error codes.
     * All unstructured, arbitrary, or un-whitelisted text is fail-closed to [REDACTED_EXCEPTION_MESSAGE].
     */
    fun sanitizeThrowableMessage(message: String?): String {
        if (message.isNullOrBlank()) return "[NO_MESSAGE]"

        val trimmed = message.trim()
        val lower = trimmed.lowercase()

        // 1. Whitelisted system diagnostic phrases
        if (SAFE_EXCEPTION_PHRASES.contains(lower)) {
            return trimmed
        }

        // 2. Structured uppercase error codes (e.g. AUTH_REQUIRED, RATE_LIMITED)
        if (STRUCTURED_ERROR_CODE_REGEX.matches(trimmed)) {
            return trimmed
        }

        // 3. HTTP status codes (e.g. HTTP 401, HTTP 429)
        val httpMatch = HTTP_STATUS_REGEX.find(trimmed)
        if (httpMatch != null) {
            return httpMatch.value.uppercase()
        }

        // 4. Fail-closed: arbitrary unstructured exception messages are never treated as safe
        return "[REDACTED_EXCEPTION_MESSAGE]"
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
