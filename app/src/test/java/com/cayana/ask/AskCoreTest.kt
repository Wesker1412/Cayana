package com.cayana.ask

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.AskResult
import com.cayana.ask.model.EvidenceState
import com.cayana.ask.security.PermissiveAuthorizationGate
import com.cayana.ask.service.DefaultAskService
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.retrieval.DefaultMemoryRetriever
import com.cayana.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskCoreTest {

    private lateinit var database: CayanaDatabase
    private lateinit var repository: RoomMemoryRepository
    private lateinit var retriever: DefaultMemoryRetriever
    private lateinit var fakeLlm: FakeLlmProvider
    private lateinit var askService: DefaultAskService

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CayanaDatabase::class.java
        ).allowMainThreadQueries().build()

        repository = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao(),
            database = database
        )

        retriever = DefaultMemoryRetriever(
            memoryRepository = repository,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        fakeLlm = FakeLlmProvider()

        askService = DefaultAskService(
            memoryRetriever = retriever,
            contextPackBuilder = ContextPackBuilder(),
            llmProvider = fakeLlm,
            authorizationGate = PermissiveAuthorizationGate()
        )

        runBlocking {
            repository.saveMemory(
                MemoryItem(
                    id = "flight-1",
                    sourceType = SourceType.SCREENSHOT,
                    createdAt = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli(),
                    capturedAt = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli(),
                    title = "Tokyo Flight Itinerary",
                    rawText = "Flight BR198 to Tokyo Haneda departs Nov 12 at 08:30 from Taipei Songshan.",
                    sourceUri = "content://media/external/images/101",
                    sourceUrl = "https://evaair.com/itinerary?id=99",
                    processingState = ProcessingState.COMPLETED
                )
            )

            repository.saveMemory(
                MemoryItem(
                    id = "hotel-1",
                    sourceType = SourceType.SCREENSHOT,
                    createdAt = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(),
                    capturedAt = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(),
                    title = "Shibuya Hotel Booking",
                    rawText = "Hotel booking confirmed at Shibuya Grand Hotel. Check-in Nov 12, check-out Nov 16.",
                    sourceUri = "content://media/external/images/102",
                    sourceUrl = "https://booking.com/reservation/hotel123",
                    processingState = ProcessingState.COMPLETED
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `end-to-end ask pipeline resolves sufficient evidence with sources and privacy boundaries`() = runBlocking {
        fakeLlm.defaultEvidenceState = EvidenceState.SUFFICIENT_EVIDENCE

        val result = askService.ask("When is my flight to Tokyo?")
        assertTrue("Result should be Success", result is AskResult.Success)
        val success = result as AskResult.Success

        assertEquals(EvidenceState.SUFFICIENT_EVIDENCE, success.evidenceState)
        assertTrue(success.answer.contains("flight-1"))
        assertEquals(1, success.sources.size)

        val source = success.sources.first()
        assertEquals("flight-1", source.memoryId)
        assertEquals("Tokyo Flight Itinerary", source.title)
        assertEquals(SourceType.SCREENSHOT, source.sourceType)

        // Privacy boundary verification:
        // sourceUri and sourceUrl must never enter the serialized ContextPack
        val rendered = success.contextPack.renderUntrustedMemories()
        assertFalse(rendered.contains("content://media/external/images/101"))
        assertFalse(rendered.contains("evaair.com"))
        assertFalse(rendered.contains("sourceUri"))
    }

    @Test
    fun `distinguishes insufficient evidence when facts are incomplete`() = runBlocking {
        fakeLlm.defaultEvidenceState = EvidenceState.INSUFFICIENT_EVIDENCE

        val result = askService.ask("Shibuya Hotel WiFi password")
        assertTrue(result is AskResult.Success)
        val success = result as AskResult.Success

        assertEquals(EvidenceState.INSUFFICIENT_EVIDENCE, success.evidenceState)
        assertTrue(success.answer.contains("lack sufficient details"))
        // Memory sources still cited so user knows which record was partially relevant
        assertEquals(1, success.sources.size)
        assertEquals("hotel-1", success.sources.first().memoryId)
    }

    @Test
    fun `returns no evidence fast path without invoking llm when no memories match`() = runBlocking {
        val result = askService.ask("What was the Mars rover launch date?")
        assertTrue(result is AskResult.NoEvidence)
        val noEvidence = result as AskResult.NoEvidence

        assertTrue(noEvidence.message.contains("No relevant memory records found"))
        assertTrue(noEvidence.contextPack.items.isEmpty())

        // LLM must not be contacted when zero evidence exists locally
        assertEquals(0, fakeLlm.requestCount)
    }

    @Test
    fun `enforces context byte limit through entire ask pipeline`() = runBlocking {
        val result = askService.ask(
            query = "When is my flight to Tokyo?",
            options = AskOptions(maxContextPromptBytes = 250)
        )

        assertTrue(result is AskResult.Success)
        val success = result as AskResult.Success
        assertTrue(success.contextPack.promptContextBytes <= 250)
        assertTrue(success.contextPack.isTruncatedByByteLimit)
    }

    @Test
    fun `does not auto-cite all memories when llm returns empty citedMemoryIds`() = runBlocking {
        fakeLlm.customGenerator = { req ->
            com.cayana.ask.llm.LlmResponse(
                answer = "Answer without citations.",
                evidenceState = EvidenceState.SUFFICIENT_EVIDENCE,
                citedMemoryIds = emptyList() // LLM omitted citation IDs
            )
        }

        val result = askService.ask("When is my flight to Tokyo?")
        assertTrue(result is AskResult.Success)
        val success = result as AskResult.Success

        // Strict boundary: Must NOT fall back to citing all ContextPack items
        assertTrue("Sources must be empty when LLM did not cite any memory", success.sources.isEmpty())
    }

    @Test
    fun `filters out hallucinated citedMemoryIds not present in context pack`() = runBlocking {
        fakeLlm.customGenerator = { req ->
            com.cayana.ask.llm.LlmResponse(
                answer = "Answer citing hallucinated memory.",
                evidenceState = EvidenceState.SUFFICIENT_EVIDENCE,
                citedMemoryIds = listOf("hallucinated-memory-999", "flight-1", "non-existent-404")
            )
        }

        val result = askService.ask("When is my flight to Tokyo?")
        assertTrue(result is AskResult.Success)
        val success = result as AskResult.Success

        // Only "flight-1" actually exists in the ContextPack
        assertEquals(1, success.sources.size)
        assertEquals("flight-1", success.sources.first().memoryId)
    }

    @Test
    fun `deduplicates citedMemoryIds while preserving citation order`() = runBlocking {
        fakeLlm.customGenerator = { req ->
            com.cayana.ask.llm.LlmResponse(
                answer = "Answer with duplicate citations.",
                evidenceState = EvidenceState.SUFFICIENT_EVIDENCE,
                citedMemoryIds = listOf("flight-1", "flight-1", "hotel-1", "flight-1")
            )
        }

        val result = askService.ask("When is my flight to Tokyo?")
        assertTrue(result is AskResult.Success)
        val success = result as AskResult.Success

        val citedIds = success.sources.map { it.memoryId }
        // Note: query "When is my flight to Tokyo?" only matches flight-1 into ContextPack,
        // so hotel-1 was not in context pack and is filtered out, and flight-1 is deduplicated
        assertEquals(listOf("flight-1"), citedIds)
    }

    @Test
    fun `no evidence response never attaches citations even if llm mistakenly provided them`() = runBlocking {
        fakeLlm.customGenerator = { req ->
            com.cayana.ask.llm.LlmResponse(
                answer = "No evidence found to answer your question.",
                evidenceState = EvidenceState.NO_EVIDENCE,
                citedMemoryIds = listOf("flight-1") // Mistakenly attached
            )
        }

        val result = askService.ask("When is my flight to Tokyo?")
        assertTrue(result is AskResult.Success)
        val success = result as AskResult.Success

        assertEquals(EvidenceState.NO_EVIDENCE, success.evidenceState)
        // Strict boundary: NO_EVIDENCE must never have citations attached
        assertTrue("NO_EVIDENCE must have empty sources", success.sources.isEmpty())
    }
}
