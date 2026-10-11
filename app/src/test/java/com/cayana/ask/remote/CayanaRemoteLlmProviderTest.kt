package com.cayana.ask.remote

import com.cayana.ask.context.AskPromptTemplates
import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.LlmRequest
import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.AskResult
import com.cayana.ask.model.ContextPack
import com.cayana.ask.model.ContextPackItem
import com.cayana.ask.model.EvidenceState
import com.cayana.ask.security.AskAuthorizationGate
import com.cayana.ask.security.AuthorizationDecision
import com.cayana.ask.service.DefaultAskService
import com.cayana.retrieval.MemoryContext
import com.cayana.retrieval.MemoryRetriever
import com.cayana.retrieval.RetrievalConfidence
import com.cayana.retrieval.RetrievalQuery
import com.cayana.retrieval.RetrievalResult
import com.cayana.source.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class CayanaRemoteLlmProviderTest {

    private lateinit var mockServer: MockInferenceHttpServer
    private lateinit var installationIdProvider: AnonymousInstallationIdProvider
    private lateinit var config: RemoteEndpointConfig
    private lateinit var provider: CayanaRemoteLlmProvider

    private val fixedClock = Clock.fixed(Instant.parse("2024-10-11T10:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        mockServer = MockInferenceHttpServer()
        mockServer.start()

        installationIdProvider = InMemoryInstallationIdProvider("anon-client-uuid-12345")
        config = RemoteEndpointConfig(
            baseUrl = mockServer.baseUrl,
            allowInsecureLocalhost = true,
            connectTimeoutMs = 1000,
            readTimeoutMs = 2000
        )
        provider = CayanaRemoteLlmProvider(
            client = DefaultRemoteInferenceClient(),
            config = config,
            installationIdProvider = installationIdProvider
        )
    }

    @After
    fun tearDown() {
        mockServer.stop()
    }

    private fun createSampleContextPack(
        itemCount: Int = 1,
        snippetLength: Int = 50
    ): ContextPack {
        val items = (1..itemCount).map { i ->
            ContextPackItem(
                memoryId = "mem-$i",
                snippet = "Memory content snippet #$i: " + "A".repeat(snippetLength),
                capturedAt = 1728633600000L + i * 1000,
                capturedDateIso = "2024-10-11T08:00:0${i}Z",
                sourceType = SourceType.SCREENSHOT
            )
        }
        return ContextPack(
            query = "Where did I park?",
            items = items,
            promptContextBytes = 250
        )
    }

    @Test
    fun successfulInferenceWithVerifiedCitations() = runTest {
        val contextPack = createSampleContextPack(itemCount = 2)
        mockServer.responseStatusCode = 200
        mockServer.responseBody = """
            {
                "requestId": "req-999",
                "answer": "You parked on B2 floor space 42.",
                "evidenceState": "SUPPORTED",
                "citedMemoryIds": ["mem-1"],
                "model": "cayana-inference-v1",
                "usage": {
                    "promptTokens": 120,
                    "completionTokens": 25
                }
            }
        """.trimIndent()

        val request = LlmRequest(
            query = "Where did I park?",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = contextPack
        )

        val response = provider.generateAnswer(request)

        assertEquals("You parked on B2 floor space 42.", response.answer)
        assertEquals(EvidenceState.SUFFICIENT_EVIDENCE, response.evidenceState)
        assertEquals(listOf("mem-1"), response.citedMemoryIds)
        assertEquals(120, response.promptTokens)
        assertEquals(25, response.completionTokens)

        // Verify request payload was received by mock server
        assertEquals(1, mockServer.recordedRequests.size)
        val recorded = mockServer.recordedRequests[0]
        assertEquals("POST", recorded.method)
        assertEquals("/v1/ask", recorded.path)
        assertEquals(listOf("anon-client-uuid-12345"), recorded.headers["x-cayana-installation-id"])
        assertTrue(recorded.bodyString.contains("Where did I park?"))
        assertTrue(recorded.bodyString.contains("mem-1"))
    }

    @Test
    fun unauthorizedTransmissionBlockedWhenGateDenied() = runTest {
        val gate = object : AskAuthorizationGate {
            override suspend fun checkAuthorization(query: String, contextPack: ContextPack, providerInfo: com.cayana.ask.llm.LlmProviderInfo): AuthorizationDecision {
                return AuthorizationDecision.Denied("User denied transmission")
            }
        }

        val retriever = object : MemoryRetriever {
            override suspend fun retrieve(query: RetrievalQuery): RetrievalResult {
                return RetrievalResult(
                    query = query.text,
                    items = listOf(
                        MemoryContext(
                            memoryId = "mem-secret",
                            title = "Secret doc",
                            sourceType = SourceType.SHARED_TEXT,
                            capturedAt = 1728633600000L,
                            relevantExcerpt = "Super secret personal record",
                            relevanceScore = 1.0
                        )
                    ),
                    confidence = RetrievalConfidence.HIGH
                )
            }
        }

        val askService = DefaultAskService(
            memoryRetriever = retriever,
            contextPackBuilder = ContextPackBuilder(),
            llmProvider = provider,
            authorizationGate = gate
        )

        val result = askService.ask("What is my secret?")
        assertTrue(result is AskResult.Unauthorized)
        assertEquals("User denied transmission", (result as AskResult.Unauthorized).reason)

        // STRICT BOUNDARY ASSERTION: Zero network requests made!
        assertEquals(0, mockServer.recordedRequests.size)
    }

    @Test
    fun unauthorizedTransmissionBlockedWhenGatePending() = runTest {
        val gate = object : AskAuthorizationGate {
            override suspend fun checkAuthorization(query: String, contextPack: ContextPack, providerInfo: com.cayana.ask.llm.LlmProviderInfo): AuthorizationDecision {
                return AuthorizationDecision.Denied("Awaiting explicit confirmation")
            }
        }

        val retriever = object : MemoryRetriever {
            override suspend fun retrieve(query: RetrievalQuery): RetrievalResult {
                return RetrievalResult(
                    query = query.text,
                    items = listOf(
                        MemoryContext(
                            memoryId = "mem-1",
                            title = "Doc",
                            sourceType = SourceType.SHARED_TEXT,
                            capturedAt = 1728633600000L,
                            relevantExcerpt = "Info",
                            relevanceScore = 1.0
                        )
                    ),
                    confidence = RetrievalConfidence.HIGH
                )
            }
        }

        val askService = DefaultAskService(
            memoryRetriever = retriever,
            contextPackBuilder = ContextPackBuilder(),
            llmProvider = provider,
            authorizationGate = gate
        )

        val result = askService.ask("Query needing confirmation")
        assertTrue(result is AskResult.Unauthorized)

        // STRICT BOUNDARY: Zero bytes transmitted
        assertEquals(0, mockServer.recordedRequests.size)
    }

    @Test
    fun authorizationBypassAttemptRejectedForRemoteProvider() = runTest {
        val gate = object : AskAuthorizationGate {
            override suspend fun checkAuthorization(query: String, contextPack: ContextPack, providerInfo: com.cayana.ask.llm.LlmProviderInfo): AuthorizationDecision {
                return AuthorizationDecision.Allowed
            }
        }

        val retriever = object : MemoryRetriever {
            override suspend fun retrieve(query: RetrievalQuery): RetrievalResult {
                return RetrievalResult(
                    query = query.text,
                    items = listOf(
                        MemoryContext(
                            memoryId = "mem-1",
                            title = "Doc",
                            sourceType = SourceType.SHARED_TEXT,
                            capturedAt = 1728633600000L,
                            relevantExcerpt = "Info",
                            relevanceScore = 1.0
                        )
                    ),
                    confidence = RetrievalConfidence.HIGH
                )
            }
        }

        val askService = DefaultAskService(
            memoryRetriever = retriever,
            contextPackBuilder = ContextPackBuilder(),
            llmProvider = provider,
            authorizationGate = gate
        )

        @Suppress("DEPRECATION")
        val result = askService.ask("Bypass attempt", AskOptions(bypassAuthorizationGate = true))
        assertTrue(result is AskResult.Unauthorized)
        assertTrue((result as AskResult.Unauthorized).reason.contains("Cannot bypass authorization gate"))

        // STRICT BOUNDARY: Zero transmission
        assertEquals(0, mockServer.recordedRequests.size)
    }

    @Test
    fun hallucinatedCitationIdsFilteredOut() = runTest {
        val contextPack = createSampleContextPack(itemCount = 1) // only mem-1 exists
        mockServer.responseStatusCode = 200
        mockServer.responseBody = """
            {
                "requestId": "req-1",
                "answer": "Here is the answer.",
                "evidenceState": "SUPPORTED",
                "citedMemoryIds": ["mem-1", "mem-hallucinated-fake-999"]
            }
        """.trimIndent()

        val request = LlmRequest(
            query = "Where did I park?",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = contextPack
        )

        val response = provider.generateAnswer(request)

        // mem-hallucinated-fake-999 must be filtered out
        assertEquals(listOf("mem-1"), response.citedMemoryIds)
    }

    @Test
    fun noEvidenceStateForcesEmptyCitations() = runTest {
        val contextPack = createSampleContextPack(itemCount = 1)
        mockServer.responseStatusCode = 200
        mockServer.responseBody = """
            {
                "requestId": "req-2",
                "answer": "I found no evidence in your memory.",
                "evidenceState": "NO_EVIDENCE",
                "citedMemoryIds": ["mem-1"]
            }
        """.trimIndent()

        val request = LlmRequest(
            query = "Unrelated question",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = contextPack
        )

        val response = provider.generateAnswer(request)

        assertEquals(EvidenceState.NO_EVIDENCE, response.evidenceState)
        // Citations MUST be empty when evidenceState == NO_EVIDENCE
        assertTrue(response.citedMemoryIds.isEmpty())
    }

    @Test
    fun requestExceedingWireLimitRejectedClientSide() = runTest {
        // Construct contextPack whose serialized JSON body exceeds 32KB
        val hugeItems = (1..10).map { i ->
            ContextPackItem(
                memoryId = "mem-$i",
                snippet = "HUGE_PADDING_".repeat(300), // ~3.9KB each -> ~39KB total > 32KB
                capturedAt = 1728633600000L,
                capturedDateIso = "2024-10-11T08:00:00Z",
                sourceType = SourceType.SCREENSHOT
            )
        }
        val hugeContextPack = ContextPack(
            query = "Huge query",
            items = hugeItems,
            promptContextBytes = 15000 // below context prompt budget
        )

        val request = LlmRequest(
            query = "Huge query",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = hugeContextPack
        )

        try {
            provider.generateAnswer(request)
            fail("Expected PayloadTooLarge exception")
        } catch (e: RemoteInferenceException.PayloadTooLarge) {
            assertTrue(e.message!!.contains("transport wire budget"))
        }

        // STRICT BOUNDARY: Never sent to server
        assertEquals(0, mockServer.recordedRequests.size)
    }

    @Test
    fun questionExceedingCharacterLimitRejectedClientSide() = runTest {
        val longQuery = "A".repeat(2001)
        val request = LlmRequest(
            query = longQuery,
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = createSampleContextPack(itemCount = 1)
        )

        try {
            provider.generateAnswer(request)
            fail("Expected InvalidRequest exception")
        } catch (e: RemoteInferenceException.InvalidRequest) {
            assertTrue(e.message!!.contains("exceeds maximum limit of 2000 chars"))
        }

        assertEquals(0, mockServer.recordedRequests.size)
    }

    @Test
    fun topKExceedingLimitRejectedClientSide() = runTest {
        val items11 = (1..11).map { i ->
            ContextPackItem(
                memoryId = "mem-$i",
                snippet = "Snippet $i",
                capturedAt = 1728633600000L,
                capturedDateIso = "2024-10-11T08:00:00Z",
                sourceType = SourceType.SCREENSHOT
            )
        }
        val contextPack = ContextPack(query = "Q", items = items11, promptContextBytes = 500)
        val request = LlmRequest(
            query = "Q",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = contextPack
        )

        try {
            provider.generateAnswer(request)
            fail("Expected InvalidRequest exception")
        } catch (e: RemoteInferenceException.InvalidRequest) {
            assertTrue(e.message!!.contains("exceeds maximum Top-K limit of 10"))
        }

        assertEquals(0, mockServer.recordedRequests.size)
    }

    @Test
    fun serverHttpErrorsMappedCorrectly() = runTest {
        val request = LlmRequest(
            query = "Q",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = createSampleContextPack(itemCount = 1)
        )

        // 401 Unauthorized
        mockServer.responseStatusCode = 401
        mockServer.responseBody = """{"error":{"code":"UNAUTHORIZED","message":"Invalid credential"}}"""
        try {
            provider.generateAnswer(request)
            fail("Expected Unauthorized")
        } catch (e: RemoteInferenceException.Unauthorized) {
            assertTrue(e.message!!.contains("Unauthorized"))
        }

        // 413 Payload Too Large
        mockServer.responseStatusCode = 413
        mockServer.responseBody = """{"error":{"code":"PAYLOAD_TOO_LARGE","message":"Exceeded wire limit"}}"""
        try {
            provider.generateAnswer(request)
            fail("Expected PayloadTooLarge")
        } catch (e: RemoteInferenceException.PayloadTooLarge) {
            assertTrue(e.message!!.contains("Payload Too Large"))
        }

        // 429 Rate Limited
        mockServer.responseStatusCode = 429
        mockServer.responseBody = """{"error":{"code":"RATE_LIMITED","message":"Quota exceeded"}}"""
        try {
            provider.generateAnswer(request)
            fail("Expected RateLimited")
        } catch (e: RemoteInferenceException.RateLimited) {
            assertTrue(e.message!!.contains("Rate Limited"))
        }

        // 502 Upstream Error
        mockServer.responseStatusCode = 502
        mockServer.responseBody = """{"error":{"code":"UPSTREAM_ERROR","message":"LLM provider down"}}"""
        try {
            provider.generateAnswer(request)
            fail("Expected UpstreamError")
        } catch (e: RemoteInferenceException.UpstreamError) {
            assertTrue(e.message!!.contains("Upstream Error"))
        }

        // 500 Server Error
        mockServer.responseStatusCode = 500
        mockServer.responseBody = """{"error":{"code":"INTERNAL_ERROR","message":"Internal crash"}}"""
        try {
            provider.generateAnswer(request)
            fail("Expected ServerError")
        } catch (e: RemoteInferenceException.ServerError) {
            assertEquals(500, e.statusCode)
        }
    }

    @Test
    fun malformedJsonResponseHandledFailClosed() = runTest {
        val request = LlmRequest(
            query = "Q",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = createSampleContextPack(itemCount = 1)
        )

        mockServer.responseStatusCode = 200
        mockServer.responseBody = "{malformed-json"

        try {
            provider.generateAnswer(request)
            fail("Expected ServerError for malformed JSON")
        } catch (e: RemoteInferenceException.ServerError) {
            assertTrue(e.message!!.contains("Failed to parse server response JSON"))
        }
    }

    @Test
    fun insecureProductionHttpUrlRejected() {
        try {
            RemoteEndpointConfig(
                baseUrl = "http://api.cayana.com",
                allowInsecureLocalhost = false
            )
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Insecure HTTP protocol is prohibited"))
        }
    }

    @Test
    fun unexpectedRedirectRejected() = runTest {
        val request = LlmRequest(
            query = "Q",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = createSampleContextPack(itemCount = 1)
        )

        mockServer.responseStatusCode = 302
        mockServer.responseHeaders["Location"] = "http://evil.com"
        mockServer.responseBody = ""

        try {
            provider.generateAnswer(request)
            fail("Expected SecurityViolation for redirect")
        } catch (e: RemoteInferenceException.SecurityViolation) {
            assertTrue(e.message!!.contains("redirect 302 is strictly forbidden"))
        }
    }

    @Test
    fun networkTimeoutHandled() = runTest {
        val shortTimeoutConfig = config.copy(connectTimeoutMs = 200, readTimeoutMs = 200)
        val timeoutProvider = CayanaRemoteLlmProvider(
            client = DefaultRemoteInferenceClient(),
            config = shortTimeoutConfig,
            installationIdProvider = installationIdProvider
        )

        mockServer.simulatedDelayMs = 1000 // Server responds after 1000ms > 200ms read timeout

        val request = LlmRequest(
            query = "Q",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = createSampleContextPack(itemCount = 1)
        )

        try {
            timeoutProvider.generateAnswer(request)
            fail("Expected NetworkError timeout")
        } catch (e: RemoteInferenceException.NetworkError) {
            assertTrue(e.message!!.contains("Network communication failure"))
        }
    }

    @Test
    fun coroutineCancellationHandled() = runTest {
        mockServer.simulatedDelayMs = 3000
        val request = LlmRequest(
            query = "Q",
            systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
            contextPack = createSampleContextPack(itemCount = 1)
        )

        val deferred = async {
            provider.generateAnswer(request)
        }

        // Cancel job before it finishes
        deferred.cancelAndJoin()
        assertTrue(deferred.isCancelled)
    }

    @Test
    fun zeroApiKeyInApkOrSourceCode() {
        // Audit Android source tree to confirm NO upstream LLM API keys are hardcoded
        val mainDir = File("app/src/main")
        val suspectPatterns = listOf("sk-", "AIzaSy", "ghp_", "Bearer ")

        mainDir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.forEach { file ->
            val content = file.readText()
            for (pattern in suspectPatterns) {
                // If "Bearer " is found, ensure it is not followed by an actual secret
                if (pattern == "Bearer ") {
                    assertFalse(
                        "Potential hardcoded bearer token in ${file.path}",
                        content.contains("Bearer sk-") || content.contains("Bearer ghp_")
                    )
                } else {
                    assertFalse(
                        "Found prohibited upstream API key signature '$pattern' in ${file.path}",
                        content.contains(pattern)
                    )
                }
            }
        }
    }
}
