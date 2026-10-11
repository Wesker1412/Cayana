package com.cayana.ask.remote

import com.cayana.ask.llm.HttpWireBudgetValidator
import com.cayana.ask.llm.LlmProvider
import com.cayana.ask.llm.LlmProviderInfo
import com.cayana.ask.llm.LlmRequest
import com.cayana.ask.llm.LlmResponse
import com.cayana.ask.model.EvidenceState
import java.util.UUID

/**
 * Production remote LLM Provider communicating with the Cayana LLM API inference backend.
 *
 * Boundary Guarantees:
 * 1. isLocal = false (Strictly triggers fail-closed user authorization in DefaultAskService).
 * 2. Hard wire budget limit (<= 32KB) verified on full serialized JSON before transmission.
 * 3. Client-side input bounds: Question <= 2,000 chars, Top-K <= 10, Prompt Context <= 16KB.
 * 4. Citation boundary: filters out any hallucinated IDs not present in ContextPack items.
 * 5. NO_EVIDENCE guarantee: never returns citations if evidenceState is NO_EVIDENCE.
 * 6. Zero persistence of queries or responses.
 */
class CayanaRemoteLlmProvider(
    private val client: RemoteInferenceClient = DefaultRemoteInferenceClient(),
    private val config: RemoteEndpointConfig = RemoteEndpointConfig(),
    private val installationIdProvider: AnonymousInstallationIdProvider
) : LlmProvider, HttpWireBudgetValidator {

    override val info: LlmProviderInfo = LlmProviderInfo(
        id = PROVIDER_ID,
        name = PROVIDER_NAME,
        isLocal = false,
        modelName = MODEL_NAME
    )

    override fun validateFinalHttpRequestBytes(serializedPayloadBytes: Int, maxTransportBytes: Int): Boolean {
        return serializedPayloadBytes in 1..maxTransportBytes
    }

    override suspend fun generateAnswer(request: LlmRequest): LlmResponse {
        // 1. Client-side input bounds verification
        if (request.query.length > config.maxQuestionChars) {
            throw RemoteInferenceException.InvalidRequest(
                "Question length (${request.query.length}) exceeds maximum limit of ${config.maxQuestionChars} chars"
            )
        }

        if (request.contextPack.items.size > config.maxTopK) {
            throw RemoteInferenceException.InvalidRequest(
                "ContextPack items count (${request.contextPack.items.size}) exceeds maximum Top-K limit of ${config.maxTopK}"
            )
        }

        if (request.contextPack.promptContextBytes > config.maxContextPromptBytes) {
            throw RemoteInferenceException.PayloadTooLarge(
                "Context prompt bytes (${request.contextPack.promptContextBytes}) exceeds budget of ${config.maxContextPromptBytes} bytes"
            )
        }

        // 2. Prepare remote request payload
        val installationId = installationIdProvider.getInstallationId()
        val requestId = UUID.randomUUID().toString()
        val askRequest = RemoteAskRequest(
            installationId = installationId,
            requestId = requestId,
            question = request.query,
            contextPack = request.contextPack
        )

        // 3. Validate final serialized wire budget
        val serializedWireBytes = askRequest.toJsonString().toByteArray(Charsets.UTF_8).size
        if (!validateFinalHttpRequestBytes(serializedWireBytes, config.maxRequestBytes)) {
            throw RemoteInferenceException.PayloadTooLarge(
                "Full HTTP request body size ($serializedWireBytes bytes) exceeds transport wire budget of ${config.maxRequestBytes} bytes"
            )
        }

        // 4. Execute remote HTTPS inference
        val response = client.sendInferenceRequest(askRequest, config)

        // 5. Enforce citation and evidence boundary
        val validMemoryIds = request.contextPack.items.map { it.memoryId }.toSet()
        val sanitizedCitations = if (response.evidenceState == EvidenceState.NO_EVIDENCE) {
            emptyList()
        } else {
            response.citedMemoryIds.distinct().filter { id -> id in validMemoryIds }
        }

        return LlmResponse(
            answer = response.answer,
            evidenceState = response.evidenceState,
            citedMemoryIds = sanitizedCitations,
            promptTokens = response.promptTokens,
            completionTokens = response.completionTokens
        )
    }

    companion object {
        const val PROVIDER_ID = "cayana_remote"
        const val PROVIDER_NAME = "Cayana Remote LLM API"
        const val MODEL_NAME = "cayana-inference-v1"
    }
}
