package com.cayana.ask.service

import com.cayana.ask.context.AskPromptTemplates
import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.LlmProvider
import com.cayana.ask.llm.LlmRequest
import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.AskResult
import com.cayana.ask.model.EvidenceState
import com.cayana.ask.model.MemorySourceRef
import com.cayana.ask.security.AskAuthorizationGate
import com.cayana.ask.security.AuthorizationDecision
import com.cayana.retrieval.MemoryRetriever
import com.cayana.retrieval.RetrievalConfidence
import com.cayana.retrieval.RetrievalOptions
import com.cayana.retrieval.RetrievalQuery
import kotlinx.coroutines.CancellationException
import kotlin.system.measureTimeMillis

/**
 * Production implementation of AskService.
 *
 * Pipeline:
 * Question → MemoryRetriever (Stage 7A) → ContextPack → AskAuthorizationGate → LlmProvider → Answer + Sources
 *
 * Guaranteed boundaries:
 * 1. Zero access to Room DAOs, databases, or raw entities.
 * 2. Strict byte-bounded serialization in ContextPack.
 * 3. Read-only safety on failure or cancellation.
 */
class DefaultAskService(
    private val memoryRetriever: MemoryRetriever,
    private val contextPackBuilder: ContextPackBuilder,
    private val llmProvider: LlmProvider,
    private val authorizationGate: AskAuthorizationGate
) : AskService {

    override suspend fun ask(
        query: String,
        options: AskOptions
    ): AskResult {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            val emptyPack = contextPackBuilder.build(
                query = "",
                memoryContexts = emptyList(),
                maxBytes = options.maxContextBytes,
                clock = options.clock,
                zoneId = options.zoneId
            )
            return AskResult.NoEvidence(
                query = query,
                message = "Please enter a valid question.",
                contextPack = emptyPack
            )
        }

        try {
            // 1. Local Retrieval via MemoryRetriever (Strictly read-only)
            val retrievalQuery = RetrievalQuery(
                text = trimmedQuery,
                options = RetrievalOptions(
                    topK = options.topK,
                    clock = options.clock,
                    zoneId = options.zoneId
                )
            )
            val retrievalResult = memoryRetriever.retrieve(retrievalQuery)

            // 2. Early No-Evidence Fast Path
            if (retrievalResult.items.isEmpty() || retrievalResult.confidence == RetrievalConfidence.NONE) {
                val emptyPack = contextPackBuilder.build(
                    query = trimmedQuery,
                    memoryContexts = emptyList(),
                    maxBytes = options.maxContextBytes,
                    clock = options.clock,
                    zoneId = options.zoneId
                )
                return AskResult.NoEvidence(
                    query = trimmedQuery,
                    message = "No relevant memory records found in your personal history.",
                    contextPack = emptyPack
                )
            }

            // 3. Assemble bounded, sanitized ContextPack
            val contextPack = contextPackBuilder.build(
                query = trimmedQuery,
                memoryContexts = retrievalResult.items,
                maxBytes = options.maxContextBytes,
                clock = options.clock,
                zoneId = options.zoneId
            )

            // If byte limit pruned all items, classify as NoEvidence
            if (contextPack.items.isEmpty()) {
                return AskResult.NoEvidence(
                    query = trimmedQuery,
                    message = "Context byte limit prevented packaging memory items.",
                    contextPack = contextPack
                )
            }

            // 4. Authorization Gate check (Non-bypassable boundary for remote providers)
            @Suppress("DEPRECATION")
            if (!llmProvider.info.isLocal) {
                // Remote provider: fail-closed by default. Bypass attempts are strictly rejected.
                if (options.bypassAuthorizationGate) {
                    return AskResult.Unauthorized(
                        query = trimmedQuery,
                        reason = "Cannot bypass authorization gate for remote provider '${llmProvider.info.name}'",
                        contextPack = contextPack
                    )
                }
                val authDecision = authorizationGate.checkAuthorization(
                    query = trimmedQuery,
                    contextPack = contextPack,
                    providerInfo = llmProvider.info
                )
                if (authDecision !is AuthorizationDecision.Allowed) {
                    val reason = if (authDecision is AuthorizationDecision.Denied) {
                        authDecision.reason
                    } else {
                        "Remote transmission requires explicit user authorization"
                    }
                    return AskResult.Unauthorized(
                        query = trimmedQuery,
                        reason = reason,
                        contextPack = contextPack
                    )
                }
            } else {
                // Local offline provider
                if (!options.bypassAuthorizationGate) {
                    val authDecision = authorizationGate.checkAuthorization(
                        query = trimmedQuery,
                        contextPack = contextPack,
                        providerInfo = llmProvider.info
                    )
                    if (authDecision is AuthorizationDecision.Denied) {
                        return AskResult.Unauthorized(
                            query = trimmedQuery,
                            reason = authDecision.reason,
                            contextPack = contextPack
                        )
                    }
                }
            }

            // 5. Send bounded request to LlmProvider
            val llmRequest = LlmRequest(
                query = trimmedQuery,
                systemPrompt = AskPromptTemplates.SYSTEM_PROMPT,
                contextPack = contextPack,
                options = options
            )

            var responseAnswer: String
            var responseEvidenceState: EvidenceState
            var responseCitedIds: List<String>
            val latencyMs = measureTimeMillis {
                val response = llmProvider.generateAnswer(llmRequest)
                responseAnswer = response.answer
                responseEvidenceState = response.evidenceState
                responseCitedIds = response.citedMemoryIds
            }

            // 6. Map cited items to MemorySourceRef (Strict Evidence & Citation Boundary)
            val citations: List<MemorySourceRef> = if (responseEvidenceState == EvidenceState.NO_EVIDENCE) {
                // NO_EVIDENCE must never have citations attached
                emptyList()
            } else if (responseCitedIds.isEmpty()) {
                // Never auto-cite all memories when LLM omitted citedMemoryIds
                emptyList()
            } else {
                val sourceMap = contextPack.items.associateBy { it.memoryId }
                // Deduplicate while preserving citation order; only accept IDs that exist in ContextPack
                responseCitedIds.distinct().mapNotNull { id ->
                    sourceMap[id]?.let { item ->
                        val matchingOriginal = retrievalResult.items.find { it.memoryId == id }
                        MemorySourceRef(
                            memoryId = item.memoryId,
                            title = matchingOriginal?.title,
                            sourceType = item.sourceType,
                            capturedAt = item.capturedAt
                        )
                    }
                }
            }

            return AskResult.Success(
                query = trimmedQuery,
                answer = responseAnswer,
                evidenceState = responseEvidenceState,
                sources = citations,
                contextPack = contextPack,
                modelUsed = llmProvider.info.modelName,
                latencyMs = latencyMs
            )
        } catch (e: CancellationException) {
            // Rethrow coroutine cancellation to preserve structured concurrency
            throw e
        } catch (e: Throwable) {
            return AskResult.Error(
                query = trimmedQuery,
                message = e.message ?: "An unexpected error occurred during Ask processing",
                cause = e
            )
        }
    }
}
