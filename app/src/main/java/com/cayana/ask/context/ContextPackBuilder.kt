package com.cayana.ask.context

import com.cayana.ask.model.ContextPack
import com.cayana.ask.model.ContextPackItem
import com.cayana.retrieval.MemoryContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds minimal, bounded ContextPack instances from Stage 7A MemoryContext.
 *
 * Enforces:
 * 1. Minimal schema (memoryId, sanitized snippet, timestamp, sourceType).
 * 2. Absolute omission of sourceUri and default stripping of sourceUrl.
 * 3. Sanitization of credentials, tokens, and recovery keys.
 * 4. True serialized UTF-8 byte boundary enforcement (not heuristic char estimation).
 */
class ContextPackBuilder {

    /**
     * Builds and serializes a ContextPack, enforcing hard maxBytes limit.
     *
     * @param query Natural language user query.
     * @param memoryContexts Ranked items returned by MemoryRetriever.
     * @param maxBytes Strict wire UTF-8 byte upper bound.
     * @param clock Time provider.
     * @param zoneId Timezone for formatting ISO timestamps.
     */
    fun build(
        query: String,
        memoryContexts: List<MemoryContext>,
        maxBytes: Int,
        clock: Clock = Clock.systemDefaultZone(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ContextPack {
        require(maxBytes > 0) { "maxBytes must be greater than 0, got $maxBytes" }

        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zoneId)

        // 1. Map to minimal ContextPackItems with sanitization
        val initialItems = memoryContexts.map { ctx ->
            val formattedDate = try {
                formatter.format(Instant.ofEpochMilli(ctx.capturedAt))
            } catch (_: Exception) {
                Instant.ofEpochMilli(ctx.capturedAt).toString()
            }

            // Exclude sourceUri completely. Strip sourceUrl by default.
            // Combine sanitized title and relevantExcerpt into snippet.
            val rawSnippet = buildString {
                if (!ctx.title.isNullOrBlank()) {
                    append("Title: ").append(ctx.title.trim()).append("\n")
                }
                append(ctx.relevantExcerpt.trim())
                if (!ctx.eventSummary.isNullOrBlank()) {
                    append("\nEvent: ").append(ctx.eventSummary.trim())
                }
            }
            val sanitizedSnippet = ContextSanitizer.sanitize(rawSnippet)

            ContextPackItem(
                memoryId = ctx.memoryId,
                snippet = sanitizedSnippet,
                capturedAt = ctx.capturedAt,
                capturedDateIso = formattedDate,
                sourceType = ctx.sourceType
            )
        }

        // 2. Iteratively enforce hard wire byte limit
        var currentItems = initialItems.toMutableList()
        var wasTruncated = false

        while (currentItems.size > 1) {
            val serializedBytes = calculateWireBytes(query, currentItems)
            if (serializedBytes <= maxBytes) {
                break
            }
            // Byte limit exceeded: prune the lowest-relevance item from the end
            currentItems.removeAt(currentItems.lastIndex)
            wasTruncated = true
        }

        // If even a single item exceeded maxBytes, truncate its snippet
        if (currentItems.size == 1) {
            val singleBytes = calculateWireBytes(query, currentItems)
            if (singleBytes > maxBytes) {
                wasTruncated = true
                val firstItem = currentItems.first()
                val overheadBytes = calculateWireBytes(query, listOf(firstItem.copy(snippet = "")))
                val availableBytesForSnippet = maxBytes - overheadBytes - TRUNCATION_INDICATOR_BYTES
                if (availableBytesForSnippet > 0) {
                    val truncatedSnippet = truncateStringToUtf8Bytes(firstItem.snippet, availableBytesForSnippet) + " ...[truncated]"
                    currentItems = mutableListOf(firstItem.copy(snippet = truncatedSnippet))
                } else {
                    currentItems.clear()
                }
            }
        }

        val finalBytes = calculateWireBytes(query, currentItems)
        // Hard assertion guaranteeing serialized bytes strictly <= maxBytes
        check(finalBytes <= maxBytes || (currentItems.isEmpty() && finalBytes <= maxBytes)) {
            "Internal boundary violation: ContextPack serialized bytes ($finalBytes) exceeds limit ($maxBytes)"
        }

        return ContextPack(
            query = query,
            items = currentItems,
            totalSerializedBytes = finalBytes,
            isTruncatedByByteLimit = wasTruncated,
            createdAt = clock.millis()
        )
    }

    /**
     * Calculates the exact serialized UTF-8 byte count of the prompt payload.
     */
    fun calculateWireBytes(query: String, items: List<ContextPackItem>): Int {
        val tempPack = ContextPack(
            query = query,
            items = items,
            totalSerializedBytes = 0
        )
        val renderedMemories = tempPack.renderUntrustedMemories()
        val payload = AskPromptTemplates.formatUserPrompt(query, renderedMemories)
        return payload.toByteArray(Charsets.UTF_8).size
    }

    private fun truncateStringToUtf8Bytes(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text

        // Binary search or iterative trim to ensure valid UTF-8 character boundary
        var low = 0
        var high = text.length
        var bestIndex = 0

        while (low <= high) {
            val mid = (low + high) / 2
            val sub = text.substring(0, mid)
            if (sub.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                bestIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return text.substring(0, bestIndex)
    }

    companion object {
        private val TRUNCATION_INDICATOR_BYTES = " ...[truncated]".toByteArray(Charsets.UTF_8).size
    }
}
