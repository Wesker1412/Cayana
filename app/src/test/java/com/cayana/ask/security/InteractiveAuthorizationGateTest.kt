package com.cayana.ask.security

import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.LlmProviderInfo
import com.cayana.retrieval.MemoryContext
import com.cayana.source.SourceType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InteractiveAuthorizationGateTest {

    private val builder = ContextPackBuilder()

    private val sampleMemory = MemoryContext(
        memoryId = "doc-101",
        sourceType = SourceType.SCREENSHOT,
        capturedAt = Instant.now().toEpochMilli(),
        title = "Flight Ticket",
        relevantExcerpt = "Flight to Tokyo departing at 10:00 AM.",
        relevanceScore = 0.9
    )

    private val localProviderInfo = LlmProviderInfo(
        id = "local-provider",
        name = "Local Offline LLM",
        isLocal = true,
        modelName = "local-v1"
    )

    private val remoteProviderInfo = LlmProviderInfo(
        id = "cayana_remote",
        name = "Cayana Remote LLM API",
        isLocal = false,
        modelName = "cayana-inference-v1"
    )

    @Test
    fun `local provider is immediately authorized without suspending for preview`() = runBlocking {
        val gate = DefaultInteractiveAuthorizationGate()
        val contextPack = builder.build("Where is flight?", listOf(sampleMemory), maxBytes = 16 * 1024)

        val decision = gate.checkAuthorization(
            query = "Where is flight?",
            contextPack = contextPack,
            providerInfo = localProviderInfo
        )

        assertTrue(decision is AuthorizationDecision.Allowed)
        assertNull(gate.pendingRequest.value)
    }

    @Test
    fun `remote provider suspends and emits pending authorization request`() = runBlocking {
        val gate = DefaultInteractiveAuthorizationGate()
        val contextPack = builder.build("Where is flight?", listOf(sampleMemory), maxBytes = 16 * 1024)

        val checkDeferred = async {
            gate.checkAuthorization(
                query = "Where is flight?",
                contextPack = contextPack,
                providerInfo = remoteProviderInfo
            )
        }

        // Wait until pending request is available in flow
        val pending = gate.pendingRequest.first { it != null }
        assertNotNull(pending)
        assertEquals("Where is flight?", pending!!.query)
        assertEquals("Cayana Remote LLM API", pending.providerInfo.name)
        assertEquals(1, pending.contextPack.items.size)
        assertEquals("doc-101", pending.contextPack.items[0].memoryId)

        // Approve request
        gate.approve(pending.id)

        val decision = checkDeferred.await()
        assertTrue(decision is AuthorizationDecision.Allowed)
        assertNull(gate.pendingRequest.value)
    }

    @Test
    fun `remote provider rejection denies transmission fail-closed`() = runBlocking {
        val gate = DefaultInteractiveAuthorizationGate()
        val contextPack = builder.build("Where is flight?", listOf(sampleMemory), maxBytes = 16 * 1024)

        val checkDeferred = async {
            gate.checkAuthorization(
                query = "Where is flight?",
                contextPack = contextPack,
                providerInfo = remoteProviderInfo
            )
        }

        val pending = gate.pendingRequest.first { it != null }
        assertNotNull(pending)

        // User explicitly rejects
        gate.reject(pending!!.id)

        val decision = checkDeferred.await()
        assertTrue(decision is AuthorizationDecision.Denied)
        val denied = decision as AuthorizationDecision.Denied
        assertTrue(denied.reason.contains("declined"))
        assertNull(gate.pendingRequest.value)
    }
}
