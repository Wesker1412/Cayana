package com.cayana.ask.security

import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.AskResult
import com.cayana.ask.service.DefaultAskService
import com.cayana.retrieval.MemoryContext
import com.cayana.retrieval.MemoryRetriever
import com.cayana.retrieval.RetrievalConfidence
import com.cayana.retrieval.RetrievalQuery
import com.cayana.retrieval.RetrievalResult
import com.cayana.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AuthorizationGateTest {

    private val builder = ContextPackBuilder()

    private val sampleMemory = MemoryContext(
        memoryId = "doc-42",
        sourceType = SourceType.SCREENSHOT,
        capturedAt = Instant.now().toEpochMilli(),
        title = "Personal Diary",
        relevantExcerpt = "Private diary entry.",
        relevanceScore = 0.95
    )

    private val fakeRetriever = object : MemoryRetriever {
        override suspend fun retrieve(query: RetrievalQuery): RetrievalResult {
            return RetrievalResult(
                query = query.text,
                items = listOf(sampleMemory),
                confidence = RetrievalConfidence.HIGH
            )
        }
    }

    @Test
    fun `permissive gate authorizes transmission and proceeds to llm`() = runBlocking {
        val fakeLlm = FakeLlmProvider()
        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = fakeLlm,
            authorizationGate = PermissiveAuthorizationGate()
        )

        val result = service.ask("What is written in diary?")
        assertTrue(result is AskResult.Success)
        assertEquals(1, fakeLlm.requestCount)
    }

    @Test
    fun `reviewable gate denies transmission and halts before contacting llm`() = runBlocking {
        val fakeLlm = FakeLlmProvider()
        var inspectedQuery: String? = null
        var inspectedItemsCount = -1

        val reviewableGate = ReviewableAuthorizationGate { query, contextPack, providerInfo ->
            inspectedQuery = query
            inspectedItemsCount = contextPack.items.size
            // User explicitly rejects transmission of private diary
            false
        }

        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = fakeLlm,
            authorizationGate = reviewableGate
        )

        val result = service.ask("What is written in diary?")
        assertTrue("Expected Unauthorized result", result is AskResult.Unauthorized)
        val unauthorized = result as AskResult.Unauthorized

        assertEquals("What is written in diary?", inspectedQuery)
        assertEquals(1, inspectedItemsCount)
        assertTrue(unauthorized.reason.contains("User declined transmission"))
        // Strict boundary: LLM must NEVER have been called!
        assertEquals(0, fakeLlm.requestCount)
    }

    @Test
    fun `bypass authorization option skips gate evaluation`() = runBlocking {
        val fakeLlm = FakeLlmProvider()
        val alwaysDenyGate = ReviewableAuthorizationGate { _, _, _ -> false }

        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = fakeLlm,
            authorizationGate = alwaysDenyGate
        )

        val result = service.ask(
            query = "What is written in diary?",
            options = AskOptions(bypassAuthorizationGate = true)
        )

        assertTrue(result is AskResult.Success)
        assertEquals(1, fakeLlm.requestCount)
    }
}
