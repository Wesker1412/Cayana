package com.cayana.ask.context

import com.cayana.ask.model.ContextPackItem
import com.cayana.retrieval.MemoryContext
import com.cayana.retrieval.RetrievalSignal
import com.cayana.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ContextPackBuilderTest {

    private val fixedZone = ZoneId.of("Asia/Taipei")
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), fixedZone)
    private val builder = ContextPackBuilder()

    @Test
    fun `enforces minimal schema and strictly excludes sourceUri and sourceUrl`() {
        val memoryContext = MemoryContext(
            memoryId = "mem-001",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = Instant.parse("2026-10-15T10:00:00Z").toEpochMilli(),
            title = "Personal Tax Document",
            relevantExcerpt = "Filing summary for 2026 tax return.",
            sourceUrl = "https://sensitive-taxes.internal/user?token=secret123",
            sourceExists = true,
            relevanceScore = 0.95,
            matchedSignals = setOf(RetrievalSignal.EXACT_TITLE),
            eventSummary = null
        )

        val contextPack = builder.build(
            query = "Where is my tax document?",
            memoryContexts = listOf(memoryContext),
            maxBytes = 4096,
            clock = fixedClock,
            zoneId = fixedZone
        )

        assertEquals(1, contextPack.items.size)
        val item: ContextPackItem = contextPack.items.first()

        assertEquals("mem-001", item.memoryId)
        assertEquals(SourceType.SCREENSHOT, item.sourceType)
        assertTrue(item.snippet.contains("Personal Tax Document"))
        assertTrue(item.snippet.contains("Filing summary for 2026 tax return."))

        // Verify sourceUrl is NOT present in the untrusted memory block or item snippet
        assertFalse(item.snippet.contains("sensitive-taxes.internal"))
        assertFalse(item.snippet.contains("secret123"))

        val renderedPrompt = contextPack.renderUntrustedMemories()
        assertFalse(renderedPrompt.contains("sensitive-taxes.internal"))
        assertFalse(renderedPrompt.contains("sourceUri"))
        assertFalse(renderedPrompt.contains("sourceUrl"))
    }

    @Test
    fun `enforces hard wire byte boundary by pruning lowest-ranked items`() {
        val items = (1..5).map { i ->
            MemoryContext(
                memoryId = "mem-$i",
                sourceType = SourceType.SCREENSHOT,
                capturedAt = Instant.parse("2026-10-15T10:00:00Z").toEpochMilli() + i * 1000,
                title = "Item Number $i",
                relevantExcerpt = "This is a detailed excerpt description for memory item number $i with extra padding text to increase byte size.",
                sourceUrl = null,
                sourceExists = true,
                relevanceScore = 1.0 - (i * 0.1),
                matchedSignals = emptySet(),
                eventSummary = null
            )
        }

        val wireBytesUnbounded = builder.calculateWireBytes(
            "Query testing byte budget",
            items.map {
                ContextPackItem(it.memoryId, it.relevantExcerpt, it.capturedAt, "2026-10-15T18:00:00+08:00", it.sourceType)
            }
        )
        assertTrue("Expected wire bytes to exceed 500 bytes", wireBytesUnbounded > 500)

        val maxBytes = 450
        val pack = builder.build(
            query = "Query testing byte budget",
            memoryContexts = items,
            maxBytes = maxBytes,
            clock = fixedClock,
            zoneId = fixedZone
        )

        assertTrue("Must be marked as truncated", pack.isTruncatedByByteLimit)
        assertTrue("Item count must be reduced", pack.items.size < 5)
        assertTrue("Total wire bytes (${pack.totalSerializedBytes}) must be <= maxBytes ($maxBytes)",
            pack.totalSerializedBytes <= maxBytes)

        // Verify higher relevance items (mem-1, mem-2) are preserved before lower relevance ones
        assertEquals("mem-1", pack.items.first().memoryId)
    }

    @Test
    fun `truncates snippet of single oversized item to strictly satisfy byte bound`() {
        val largeText = "A".repeat(2000)
        val memoryContext = MemoryContext(
            memoryId = "oversized-1",
            sourceType = SourceType.PHOTO,
            capturedAt = Instant.parse("2026-10-15T10:00:00Z").toEpochMilli(),
            title = "Oversized Title",
            relevantExcerpt = largeText,
            sourceUrl = null,
            sourceExists = true,
            relevanceScore = 0.9,
            matchedSignals = emptySet(),
            eventSummary = null
        )

        val maxBytes = 350
        val pack = builder.build(
            query = "Short query",
            memoryContexts = listOf(memoryContext),
            maxBytes = maxBytes,
            clock = fixedClock,
            zoneId = fixedZone
        )

        assertTrue(pack.isTruncatedByByteLimit)
        assertTrue("Total serialized bytes (${pack.totalSerializedBytes}) must be <= $maxBytes",
            pack.totalSerializedBytes <= maxBytes)
        if (pack.items.isNotEmpty()) {
            assertTrue(pack.items.first().snippet.contains("[truncated]"))
        }
    }

    @Test
    fun `accurately measures multi-byte utf8 characters instead of relying on character length`() {
        // '繁體中文測試' each char is 3 UTF-8 bytes (6 chars = 18 bytes)
        val chineseSnippet = "繁體中文測試記憶內容".repeat(20) // 10 chars * 3 bytes * 20 = 600 bytes
        val memoryContext = MemoryContext(
            memoryId = "zh-1",
            sourceType = SourceType.RECORDING,
            capturedAt = Instant.parse("2026-10-15T10:00:00Z").toEpochMilli(),
            title = "錄音摘要",
            relevantExcerpt = chineseSnippet,
            sourceUrl = null,
            sourceExists = true,
            relevanceScore = 0.8,
            matchedSignals = emptySet(),
            eventSummary = null
        )

        val maxBytes = 250
        val pack = builder.build(
            query = "查詢中文內容",
            memoryContexts = listOf(memoryContext),
            maxBytes = maxBytes,
            clock = fixedClock,
            zoneId = fixedZone
        )

        assertTrue(pack.isTruncatedByByteLimit)
        assertTrue("Total bytes (${pack.totalSerializedBytes}) must not exceed $maxBytes",
            pack.totalSerializedBytes <= maxBytes)
        // Verify wire calculation directly
        val rawWireBytes = pack.renderUntrustedMemories().toByteArray(Charsets.UTF_8).size
        assertTrue(rawWireBytes <= maxBytes)
    }
}
