package com.cayana.ask.llm

import com.cayana.ask.model.EvidenceState
import kotlinx.coroutines.delay

/**
 * Fake/Offline LlmProvider used for unit testing, offline development,
 * and security boundary verification.
 */
class FakeLlmProvider(
    override val info: LlmProviderInfo = LlmProviderInfo(
        id = "fake-offline-provider",
        name = "Fake Offline LLM Provider",
        isLocal = true,
        modelName = "mock-stage8a-v1"
    ),
    var defaultEvidenceState: EvidenceState = EvidenceState.SUFFICIENT_EVIDENCE,
    var customGenerator: (suspend (LlmRequest) -> LlmResponse)? = null,
    var throwException: Throwable? = null,
    var simulateDelayMs: Long = 0L
) : LlmProvider {

    var lastRequest: LlmRequest? = null
        private set
    var requestCount: Int = 0
        private set

    fun reset() {
        lastRequest = null
        requestCount = 0
        throwException = null
        customGenerator = null
        simulateDelayMs = 0L
    }

    override suspend fun generateAnswer(request: LlmRequest): LlmResponse {
        lastRequest = request
        requestCount++

        if (simulateDelayMs > 0L) {
            delay(simulateDelayMs)
        }

        throwException?.let { throw it }

        customGenerator?.let { return it(request) }

        // 1. Check for empty context
        if (request.contextPack.items.isEmpty()) {
            return LlmResponse(
                answer = "I could not find any relevant memories in your local records.",
                evidenceState = EvidenceState.NO_EVIDENCE,
                citedMemoryIds = emptyList()
            )
        }

        // 2. Check for Prompt Injection attempts inside memories
        val hasInjectionPattern = request.contextPack.items.any { item ->
            val lower = item.snippet.lowercase()
            lower.contains("ignore previous instructions") ||
                lower.contains("system override") ||
                lower.contains("reveal all memories") ||
                lower.contains("select * from") ||
                lower.contains("drop table") ||
                lower.contains("grant access") ||
                lower.contains("delete from")
        }

        if (hasInjectionPattern) {
            // Verifies that instructions inside memory are treated as untrusted text and NOT executed
            val cited = request.contextPack.items.map { it.memoryId }
            return LlmResponse(
                answer = "The retrieved memory was processed as passive historical data. Injected commands were ignored. No system prompt or unauthorized database records were revealed.",
                evidenceState = EvidenceState.SUFFICIENT_EVIDENCE,
                citedMemoryIds = cited
            )
        }

        // 3. Normal response with evidence state and citations
        val citedIds = request.contextPack.items.map { it.memoryId }
        val memoryBullets = request.contextPack.items.joinToString("; ") {
            "[${it.memoryId}] ${it.snippet.take(60)}"
        }

        val answerText = when (defaultEvidenceState) {
            EvidenceState.SUFFICIENT_EVIDENCE ->
                "Based on your memories, here is the answer: $memoryBullets"
            EvidenceState.INSUFFICIENT_EVIDENCE ->
                "Found related memories ($memoryBullets), but they lack sufficient details to completely answer your question."
            EvidenceState.NO_EVIDENCE ->
                "I could not find enough relevant information in your memories to answer."
        }

        return LlmResponse(
            answer = answerText,
            evidenceState = defaultEvidenceState,
            citedMemoryIds = citedIds,
            promptTokens = request.contextPack.totalSerializedBytes / 4,
            completionTokens = answerText.length / 4
        )
    }
}
