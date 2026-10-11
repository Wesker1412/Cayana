package com.cayana.ask.context

/**
 * Context Sanitizer for Cayana Ask.
 * Enforces privacy boundaries before context enters the LLM layer:
 * - Redacts Bearer and JWT tokens
 * - Redacts API keys and generic secrets
 * - Redacts credentials and passwords
 * - Redacts Cayana / crypto recovery keys, backup keys, and seed phrases
 * - Redacts local file / content URIs
 * - Strips sensitive URL query parameters, fragments, and credentials
 */
object ContextSanitizer {

    // Recovery Key & Seed Phrase patterns
    private val RECOVERY_KEY_PATTERN = Regex(
        "(?i)\\b(recovery[\\s_-]?key|backup[\\s_-]?key)\\s*[:=]\\s*([A-Za-z0-9\\-_]+)"
    )
    private val SEED_PHRASE_PATTERN = Regex(
        "(?i)\\b(seed[\\s_-]?phrase|mnemonic)\\s*[:=]\\s*([^\\r\\n,;]+?)(?=\\s+(?:and\\s+)?(?:recovery[\\s_-]?key|backup[\\s_-]?key|seed[\\s_-]?phrase|mnemonic|password|secret|bearer)|\\s*$)"
    )
    private val STANDALONE_RECOVERY_KEY_BLOCK = Regex(
        "\\b[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}\\b"
    )

    // Bearer & JWT tokens
    private val BEARER_TOKEN_PATTERN = Regex(
        "(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*"
    )
    private val JWT_PATTERN = Regex(
        "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"
    )

    // Common API Key prefixes
    private val API_KEY_PATTERN = Regex(
        "\\b(?:sk|pk|api[_-]?key)[-_A-Za-z0-9]{16,}\\b"
    )
    private val GOOGLE_API_KEY_PATTERN = Regex(
        "\\bAIza[0-9A-Za-z\\-_]{35}\\b"
    )

    // Password & Credential patterns
    private val CREDENTIAL_PATTERN = Regex(
        "(?i)\\b(password|passwd|secret|api[_-]?secret|private[_-]?key)\\s*[:=]\\s*([^\\s\\r\\n,;]+)"
    )
    private val BASIC_AUTH_PATTERN = Regex(
        "(?i)\\bBasic\\s+[A-Za-z0-9+/=]{10,}\\b"
    )

    // Local device URIs
    private val LOCAL_URI_PATTERN = Regex(
        "\\b(content|file)://[^\\s\"'<>]+"
    )

    // Web URLs with userinfo, query params or fragments
    private val WEB_URL_SANITIZE_PATTERN = Regex(
        "https?://(?:[^@\\s]+@)?([^/?#\\s]+)(/[^?#\\s]*)?(?:\\?[^#\\s]*)?(?:#[^\\s]*)?"
    )

    /**
     * Sanitizes raw text snippet by redacting secrets, credentials, tokens, and URIs.
     */
    fun sanitize(rawText: String?): String {
        if (rawText.isNullOrBlank()) return ""

        var sanitized = rawText

        // 1. Redact explicit Recovery Keys & Seed Phrases
        sanitized = RECOVERY_KEY_PATTERN.replace(sanitized) { matchResult ->
            val label = matchResult.groupValues[1]
            "$label: [REDACTED_RECOVERY_KEY]"
        }
        sanitized = SEED_PHRASE_PATTERN.replace(sanitized) { matchResult ->
            val label = matchResult.groupValues[1]
            "$label: [REDACTED_RECOVERY_KEY]"
        }
        sanitized = STANDALONE_RECOVERY_KEY_BLOCK.replace(sanitized, "[REDACTED_RECOVERY_KEY]")

        // 2. Redact Bearer & JWT tokens
        sanitized = BEARER_TOKEN_PATTERN.replace(sanitized, "Bearer [REDACTED_TOKEN]")
        sanitized = JWT_PATTERN.replace(sanitized, "[REDACTED_JWT]")

        // 3. Redact API keys
        sanitized = API_KEY_PATTERN.replace(sanitized, "[REDACTED_API_KEY]")
        sanitized = GOOGLE_API_KEY_PATTERN.replace(sanitized, "[REDACTED_API_KEY]")

        // 4. Redact Passwords & Credentials
        sanitized = CREDENTIAL_PATTERN.replace(sanitized) { matchResult ->
            val label = matchResult.groupValues[1]
            "$label: [REDACTED_CREDENTIAL]"
        }
        sanitized = BASIC_AUTH_PATTERN.replace(sanitized, "Basic [REDACTED_CREDENTIAL]")

        // 5. Redact Local Android / file URIs
        sanitized = LOCAL_URI_PATTERN.replace(sanitized, "[REDACTED_URI]")

        // 6. Sanitize Web URLs: strip userinfo, query strings and fragments
        sanitized = WEB_URL_SANITIZE_PATTERN.replace(sanitized) { matchResult ->
            val host = matchResult.groupValues[1]
            val path = matchResult.groupValues[2]
            val protocol = if (matchResult.value.startsWith("https://", ignoreCase = true)) "https://" else "http://"
            "$protocol$host$path"
        }

        return sanitized
    }
}
