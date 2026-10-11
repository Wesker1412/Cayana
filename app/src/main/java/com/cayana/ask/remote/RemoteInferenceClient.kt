package com.cayana.ask.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

interface RemoteInferenceClient {
    suspend fun sendInferenceRequest(
        request: RemoteAskRequest,
        config: RemoteEndpointConfig
    ): RemoteAskResponse
}

class DefaultRemoteInferenceClient : RemoteInferenceClient {

    override suspend fun sendInferenceRequest(
        request: RemoteAskRequest,
        config: RemoteEndpointConfig
    ): RemoteAskResponse = withContext(Dispatchers.IO) {
        // 1. Serialize request to JSON bytes and enforce transport wire limit BEFORE any network transmission
        val serializedBytes = request.toJsonString().toByteArray(Charsets.UTF_8)
        if (serializedBytes.size > config.maxRequestBytes) {
            throw RemoteInferenceException.PayloadTooLarge(
                "Request wire body (${serializedBytes.size} bytes) exceeds transport wire limit of ${config.maxRequestBytes} bytes"
            )
        }

        // 2. Validate URL security scheme
        try {
            RemoteEndpointConfig.validateEndpointUrl(config.baseUrl, config.allowInsecureLocalhost)
        } catch (e: SecurityException) {
            throw RemoteInferenceException.SecurityViolation(e.message ?: "Endpoint protocol rejected")
        } catch (e: Exception) {
            throw RemoteInferenceException.InvalidRequest("Invalid endpoint configuration: ${e.message}")
        }

        val targetUrl = try {
            val normalizedBase = config.baseUrl.trimEnd('/')
            URL("$normalizedBase/v1/ask")
        } catch (e: Exception) {
            throw RemoteInferenceException.InvalidRequest("Failed to construct endpoint URL: ${e.message}")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                // Security: disallow arbitrary redirects (3xx) to protect against SSRF & protocol downgrade
                instanceFollowRedirects = false
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Content-Length", serializedBytes.size.toString())
                setRequestProperty("X-Cayana-Installation-Id", request.installationId)
                setRequestProperty("X-Cayana-Request-Id", request.requestId)
            }

            // 3. Write request wire bytes
            connection.outputStream.use { os ->
                os.write(serializedBytes)
                os.flush()
            }

            // 4. Read response
            val responseCode = connection.responseCode

            // Reject redirects
            if (responseCode in 300..399) {
                throw RemoteInferenceException.SecurityViolation(
                    "Unexpected HTTP redirect $responseCode is strictly forbidden for remote inference"
                )
            }

            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = readBoundedStream(inputStream, config.maxResponseBytes)

            if (responseCode in 200..299) {
                try {
                    return@withContext RemoteAskResponse.fromJsonString(responseBody)
                } catch (e: RemoteInferenceException) {
                    throw e
                } catch (e: Exception) {
                    throw RemoteInferenceException.ServerError(
                        responseCode,
                        "Failed to parse server response JSON: ${e.message}"
                    )
                }
            } else {
                // Try parsing structured error from server response
                try {
                    RemoteAskResponse.fromJsonString(responseBody)
                } catch (e: RemoteInferenceException) {
                    throw e
                } catch (_: Exception) {
                    // Fallback to HTTP status mapping if not structured
                }

                throw when (responseCode) {
                    400 -> RemoteInferenceException.InvalidRequest("Server rejected request (HTTP 400)")
                    401 -> RemoteInferenceException.Unauthorized("Server rejected credentials (HTTP 401)")
                    413 -> RemoteInferenceException.PayloadTooLarge("Server rejected wire payload size (HTTP 413)")
                    429 -> RemoteInferenceException.RateLimited("Rate limit exceeded on server (HTTP 429)")
                    502 -> RemoteInferenceException.UpstreamError("Upstream provider failure (HTTP 502)")
                    else -> RemoteInferenceException.ServerError(responseCode, "Server returned HTTP $responseCode")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteInferenceException) {
            throw e
        } catch (e: Exception) {
            throw RemoteInferenceException.NetworkError("Network communication failure: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBoundedStream(stream: InputStream?, maxBytes: Int): String {
        if (stream == null) return ""
        val buffer = ByteArray(4096)
        val baos = ByteArrayOutputStream()
        var totalRead = 0

        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalRead += read
                if (totalRead > maxBytes) {
                    throw RemoteInferenceException.PayloadTooLarge(
                        "Response stream exceeded maximum allowed size of $maxBytes bytes"
                    )
                }
                baos.write(buffer, 0, read)
            }
        }

        return baos.toString(Charsets.UTF_8.name())
    }
}
