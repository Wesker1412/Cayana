package com.cayana.ask.security

import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.llm.LlmProviderInfo
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
    fun `permissive gate authorizes transmission and proceeds to llm for local provider`() = runBlocking {
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
    fun `permissive gate strictly denies transmission for remote provider`() = runBlocking {
        val remoteLlm = FakeLlmProvider(
            info = LlmProviderInfo(
                id = "remote-test",
                name = "Remote Cloud Provider",
                isLocal = false,
                modelName = "cloud-model-v1"
            )
        )
        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = remoteLlm,
            authorizationGate = PermissiveAuthorizationGate()
        )

        val result = service.ask("What is written in diary?")
        assertTrue("Expected Unauthorized for remote provider on permissive gate", result is AskResult.Unauthorized)
        val unauthorized = result as AskResult.Unauthorized
        assertTrue(unauthorized.reason.contains("strictly limited to local offline providers"))
        assertEquals(0, remoteLlm.requestCount)
    }

    @Test
    fun `remote provider without approval fails closed`() = runBlocking {
        val remoteLlm = FakeLlmProvider(
            info = LlmProviderInfo(
                id = "remote-test",
                name = "Remote Cloud Provider",
                isLocal = false,
                modelName = "cloud-model-v1"
            )
        )
        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = remoteLlm,
            authorizationGate = FailClosedAuthorizationGate()
        )

        val result = service.ask("What is written in diary?")
        assertTrue(result is AskResult.Unauthorized)
        assertEquals(0, remoteLlm.requestCount)
    }

    @Test
    fun `remote provider strictly rejects bypass authorization attempts`() = runBlocking {
        val remoteLlm = FakeLlmProvider(
            info = LlmProviderInfo(
                id = "remote-test",
                name = "Remote Cloud Provider",
                isLocal = false,
                modelName = "cloud-model-v1"
            )
        )
        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = remoteLlm,
            authorizationGate = ReviewableAuthorizationGate { _, _, _ -> false }
        )

        @Suppress("DEPRECATION")
        val result = service.ask(
            query = "What is written in diary?",
            options = AskOptions(bypassAuthorizationGate = true)
        )

        assertTrue(result is AskResult.Unauthorized)
        val unauthorized = result as AskResult.Unauthorized
        assertTrue(unauthorized.reason.contains("Cannot bypass authorization gate for remote provider"))
        assertEquals(0, remoteLlm.requestCount)
    }

    @Test
    fun `remote provider transmits only upon explicit user approval`() = runBlocking {
        val remoteLlm = FakeLlmProvider(
            info = LlmProviderInfo(
                id = "remote-test",
                name = "Remote Cloud Provider",
                isLocal = false,
                modelName = "cloud-model-v1"
            )
        )
        var previewInspected = false
        val explicitGate = ReviewableAuthorizationGate { _, pack, _ ->
            val preview = pack.toInspectionPreview()
            assertTrue(preview.contains("doc-42"))
            previewInspected = true
            true // Explicit approval
        }

        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = remoteLlm,
            authorizationGate = explicitGate
        )

        val result = service.ask("What is written in diary?")
        assertTrue(result is AskResult.Success)
        assertTrue(previewInspected)
        assertEquals(1, remoteLlm.requestCount)
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
    fun `bypass authorization option is only respected for local provider`() = runBlocking {
        val localLlm = FakeLlmProvider()
        val alwaysDenyGate = ReviewableAuthorizationGate { _, _, _ -> false }

        val service = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = localLlm,
            authorizationGate = alwaysDenyGate
        )

        @Suppress("DEPRECATION")
        val result = service.ask(
            query = "What is written in diary?",
            options = AskOptions(bypassAuthorizationGate = true)
        )

        assertTrue(result is AskResult.Success)
        assertEquals(1, localLlm.requestCount)
    }
}
