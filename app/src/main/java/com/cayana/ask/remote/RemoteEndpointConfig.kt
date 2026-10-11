package com.cayana.ask.remote

import java.net.URI

/**
 * Strict configuration for remote Cayana LLM API inference endpoint.
 *
 * Security and Boundary Guarantees:
 * 1. Production endpoints MUST use HTTPS scheme.
 * 2. Arbitrary HTTP URLs are rejected. Insecure HTTP is only permitted on loopback (127.0.0.1/localhost)
 *    when explicitly enabled for local test runners.
 * 3. Arbitrary HTTP redirects (3xx) are strictly forbidden to mitigate SSRF and protocol downgrade attacks.
 * 4. Hard transport wire budget: max 32KB request body, max 64KB response body.
 */
data class RemoteEndpointConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val isDeployed: Boolean = false,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    val maxQuestionChars: Int = DEFAULT_MAX_QUESTION_CHARS,
    val maxTopK: Int = DEFAULT_MAX_TOP_K,
    val maxContextPromptBytes: Int = DEFAULT_MAX_CONTEXT_PROMPT_BYTES,
    val allowInsecureLocalhost: Boolean = false
) {
    /**
     * Determines whether the remote endpoint is valid, verified, and officially deployed.
     * The unconfigured placeholder domain (DEFAULT_BASE_URL) is NEVER treated as available.
     */
    val isRemoteInferenceAvailable: Boolean
        get() = (isDeployed && baseUrl != DEFAULT_BASE_URL) || (allowInsecureLocalhost && baseUrl != DEFAULT_BASE_URL)

    init {
        validateEndpointUrl(baseUrl, allowInsecureLocalhost)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.cayana.com"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val DEFAULT_MAX_REQUEST_BYTES = 32 * 1024 // 32 KB hard serialized wire limit
        const val DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024 // 64 KB response limit
        const val DEFAULT_MAX_QUESTION_CHARS = 2_000
        const val DEFAULT_MAX_TOP_K = 10
        const val DEFAULT_MAX_CONTEXT_PROMPT_BYTES = 16 * 1024 // 16 KB context prompt budget

        fun validateEndpointUrl(url: String, allowInsecureLocalhost: Boolean = false) {
            val uri = try {
                URI.create(url)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid endpoint URI: $url", e)
            }

            val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Missing URL scheme: $url")
            val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Missing host in URL: $url")

            if (scheme == "https") {
                return
            }

            if (scheme == "http") {
                val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
                if (allowInsecureLocalhost && isLoopback) {
                    return
                }
                throw SecurityException("Insecure HTTP protocol is prohibited for production inference: $url")
            }

            throw SecurityException("Unsupported protocol scheme: $scheme")
        }
    }
}
