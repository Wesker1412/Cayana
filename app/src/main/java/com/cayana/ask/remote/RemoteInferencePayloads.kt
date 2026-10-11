package com.cayana.ask.remote

import com.cayana.ask.model.ContextPack
import com.cayana.ask.model.ContextPackItem
import com.cayana.ask.model.EvidenceState

/**
 * Data Transfer Objects and JSON (de)serializers for Cayana LLM API HTTP protocol.
 * Implemented with pure Kotlin serialization to eliminate dependencies on Android SDK stubs in unit tests.
 */
data class RemoteAskRequest(
    val installationId: String,
    val requestId: String,
    val question: String,
    val contextPack: ContextPack,
    val temperature: Double = 0.1,
    val maxTokens: Int = 1024
) {
    fun toJsonString(): String = buildString {
        append("{")
        append("\"installationId\":").append(SimpleJson.escapeString(installationId)).append(",")
        append("\"requestId\":").append(SimpleJson.escapeString(requestId)).append(",")
        append("\"question\":").append(SimpleJson.escapeString(question)).append(",")

        append("\"contextPack\":{")
        append("\"query\":").append(SimpleJson.escapeString(contextPack.query)).append(",")
        append("\"promptContextBytes\":").append(contextPack.promptContextBytes).append(",")
        append("\"items\":[")
        contextPack.items.forEachIndexed { i, item ->
            if (i > 0) append(",")
            append("{")
            append("\"memoryId\":").append(SimpleJson.escapeString(item.memoryId)).append(",")
            append("\"snippet\":").append(SimpleJson.escapeString(item.snippet)).append(",")
            append("\"capturedAt\":").append(item.capturedAt).append(",")
            append("\"capturedDateIso\":").append(SimpleJson.escapeString(item.capturedDateIso)).append(",")
            append("\"sourceType\":").append(SimpleJson.escapeString(item.sourceType.name))
            append("}")
        }
        append("]},")

        append("\"options\":{")
        append("\"temperature\":").append(temperature).append(",")
        append("\"maxTokens\":").append(maxTokens)
        append("}")
        append("}")
    }
}

data class RemoteAskResponse(
    val requestId: String,
    val answer: String,
    val evidenceState: EvidenceState,
    val citedMemoryIds: List<String>,
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?
) {
    companion object {
        fun fromJsonString(jsonStr: String): RemoteAskResponse {
            val root = SimpleJson.parseObject(jsonStr)

            // Check if server returned an error envelope
            if (root.containsKey("error")) {
                @Suppress("UNCHECKED_CAST")
                val errObj = root["error"] as? Map<String, Any?>
                val code = errObj?.get("code")?.toString() ?: "UNKNOWN_ERROR"
                val msg = errObj?.get("message")?.toString() ?: "Remote server error"
                throw mapErrorCodeToException(code, msg)
            }

            val reqId = root["requestId"]?.toString() ?: ""
            val answer = root["answer"]?.toString() ?: ""
            val rawEvidence = root["evidenceState"]?.toString() ?: "INSUFFICIENT"
            val evidenceState = when (rawEvidence.uppercase()) {
                "SUPPORTED", "SUFFICIENT_EVIDENCE" -> EvidenceState.SUFFICIENT_EVIDENCE
                "INSUFFICIENT", "INSUFFICIENT_EVIDENCE" -> EvidenceState.INSUFFICIENT_EVIDENCE
                "NO_EVIDENCE" -> EvidenceState.NO_EVIDENCE
                else -> EvidenceState.INSUFFICIENT_EVIDENCE
            }

            val citedList = mutableListOf<String>()
            val rawCited = root["citedMemoryIds"]
            if (rawCited is List<*>) {
                for (item in rawCited) {
                    val s = item?.toString()?.trim()
                    if (!s.isNullOrEmpty()) {
                        citedList.add(s)
                    }
                }
            }

            val model = root["model"]?.toString() ?: "unknown"
            var promptTokens: Int? = null
            var completionTokens: Int? = null

            @Suppress("UNCHECKED_CAST")
            val usageObj = root["usage"] as? Map<String, Any?>
            if (usageObj != null) {
                promptTokens = (usageObj["promptTokens"] as? Number)?.toInt()
                completionTokens = (usageObj["completionTokens"] as? Number)?.toInt()
            }

            return RemoteAskResponse(
                requestId = reqId,
                answer = answer,
                evidenceState = evidenceState,
                citedMemoryIds = citedList,
                model = model,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )
        }

        private fun mapErrorCodeToException(code: String, message: String): RemoteInferenceException {
            return when (code.uppercase()) {
                "UNAUTHORIZED" -> RemoteInferenceException.Unauthorized(message)
                "PAYLOAD_TOO_LARGE" -> RemoteInferenceException.PayloadTooLarge(message)
                "RATE_LIMITED" -> RemoteInferenceException.RateLimited(message)
                "TOP_K_EXCEEDED", "CONTEXT_TOO_LARGE", "INVALID_REQUEST" -> RemoteInferenceException.InvalidRequest(message)
                "UPSTREAM_ERROR" -> RemoteInferenceException.UpstreamError(message)
                else -> RemoteInferenceException.ServerError(500, message)
            }
        }
    }
}

sealed class RemoteInferenceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(message: String) : RemoteInferenceException("Unauthorized: $message")
    class PayloadTooLarge(message: String) : RemoteInferenceException("Payload Too Large: $message")
    class RateLimited(message: String) : RemoteInferenceException("Rate Limited: $message")
    class InvalidRequest(message: String) : RemoteInferenceException("Invalid Request: $message")
    class UpstreamError(message: String) : RemoteInferenceException("Upstream Error: $message")
    class ServerError(val statusCode: Int, message: String) : RemoteInferenceException("HTTP $statusCode: $message")
    class NetworkError(message: String, cause: Throwable? = null) : RemoteInferenceException("Network Error: $message", cause)
    class SecurityViolation(message: String) : RemoteInferenceException("Security Violation: $message")
    class ServiceUnavailable(message: String) : RemoteInferenceException("Service Unavailable: $message")
}

/**
 * Clean, lightweight, self-contained JSON parser and serializer.
 */
object SimpleJson {
    fun escapeString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 32) {
                        append(String.format("\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    fun parseObject(jsonStr: String): Map<String, Any?> {
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            throw IllegalArgumentException("Expected JSON object starting with '{'")
        }
        val (parsed, index) = parseValue(trimmed, 0)
        val after = skipWhitespace(trimmed, index)
        if (after < trimmed.length) {
            throw IllegalArgumentException("Trailing characters after JSON object at index $after")
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw IllegalArgumentException("Expected JSON object")
    }

    private fun parseValue(src: String, start: Int): Pair<Any?, Int> {
        val idx = skipWhitespace(src, start)
        if (idx >= src.length) throw IllegalArgumentException("Unexpected end of JSON at index $start")
        return when (src[idx]) {
            '{' -> parseObjectInternal(src, idx)
            '[' -> parseArrayInternal(src, idx)
            '"' -> parseStringInternal(src, idx)
            't', 'f' -> parseBoolean(src, idx)
            'n' -> parseNull(src, idx)
            else -> parseNumber(src, idx)
        }
    }

    private fun parseObjectInternal(src: String, start: Int): Pair<Map<String, Any?>, Int> {
        var idx = start + 1
        val map = mutableMapOf<String, Any?>()
        while (idx < src.length) {
            idx = skipWhitespace(src, idx)
            if (idx >= src.length) throw IllegalArgumentException("Unclosed object")
            if (src[idx] == '}') return Pair(map, idx + 1)
            if (map.isNotEmpty()) {
                if (src[idx] != ',') throw IllegalArgumentException("Expected ',' at index $idx")
                idx = skipWhitespace(src, idx + 1)
                if (idx < src.length && src[idx] == '}') return Pair(map, idx + 1)
            }
            if (src[idx] != '"') throw IllegalArgumentException("Expected string key at index $idx")
            val (key, afterKey) = parseStringInternal(src, idx)
            idx = skipWhitespace(src, afterKey)
            if (idx >= src.length || src[idx] != ':') throw IllegalArgumentException("Expected ':' at index $idx")
            val (value, afterValue) = parseValue(src, idx + 1)
            map[key] = value
            idx = afterValue
        }
        throw IllegalArgumentException("Unclosed object")
    }

    private fun parseArrayInternal(src: String, start: Int): Pair<List<Any?>, Int> {
        var idx = start + 1
        val list = mutableListOf<Any?>()
        while (idx < src.length) {
            idx = skipWhitespace(src, idx)
            if (idx >= src.length) throw IllegalArgumentException("Unclosed array")
            if (src[idx] == ']') return Pair(list, idx + 1)
            if (list.isNotEmpty()) {
                if (src[idx] != ',') throw IllegalArgumentException("Expected ',' at index $idx")
                idx = skipWhitespace(src, idx + 1)
                if (idx < src.length && src[idx] == ']') return Pair(list, idx + 1)
            }
            val (value, afterValue) = parseValue(src, idx)
            list.add(value)
            idx = afterValue
        }
        throw IllegalArgumentException("Unclosed array")
    }

    private fun parseStringInternal(src: String, start: Int): Pair<String, Int> {
        var idx = start + 1
        val sb = StringBuilder()
        while (idx < src.length) {
            val ch = src[idx]
            if (ch == '"') return Pair(sb.toString(), idx + 1)
            if (ch == '\\') {
                idx++
                if (idx >= src.length) throw IllegalArgumentException("Unterminated escape")
                when (val esc = src[idx]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (idx + 4 >= src.length) throw IllegalArgumentException("Invalid unicode escape")
                        val hex = src.substring(idx + 1, idx + 5)
                        sb.append(hex.toInt(16).toChar())
                        idx += 4
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(ch)
            }
            idx++
        }
        throw IllegalArgumentException("Unclosed string literal")
    }

    private fun parseBoolean(src: String, start: Int): Pair<Boolean, Int> {
        if (src.startsWith("true", start)) return Pair(true, start + 4)
        if (src.startsWith("false", start)) return Pair(false, start + 5)
        throw IllegalArgumentException("Invalid boolean literal at index $start")
    }

    private fun parseNull(src: String, start: Int): Pair<Any?, Int> {
        if (src.startsWith("null", start)) return Pair(null, start + 4)
        throw IllegalArgumentException("Invalid null literal at index $start")
    }

    private fun parseNumber(src: String, start: Int): Pair<Number, Int> {
        var idx = start
        while (idx < src.length && (src[idx].isDigit() || src[idx] in "-+.eE")) {
            idx++
        }
        val numStr = src.substring(start, idx)
        val num: Number = if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
            numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number at index $start")
        } else {
            numStr.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer at index $start")
        }
        return Pair(num, idx)
    }

    private fun skipWhitespace(src: String, start: Int): Int {
        var idx = start
        while (idx < src.length && src[idx].isWhitespace()) {
            idx++
        }
        return idx
    }
}
