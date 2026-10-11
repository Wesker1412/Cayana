package com.cayana.retrieval

import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.search.MemorySearchDocumentBuilder
import com.cayana.search.data.SearchDao
import com.cayana.search.data.SearchIndexStateDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Production implementation of MemoryRetriever for Stage 7A.
 * Fully deterministic, bounded, local-first retrieval layer.
 * Reuses existing Room FTS4 index without requiring a secondary canonical database.
 */
class DefaultMemoryRetriever(
    private val memoryRepository: MemoryRepository,
    private val searchDao: SearchDao? = null,
    private val searchIndexStateDao: SearchIndexStateDao? = null,
    private val dispatchers: CoroutineDispatchers = AppDispatchers(),
    private val logger: CayanaLogger? = null
) : MemoryRetriever {

    override suspend fun retrieve(query: RetrievalQuery): RetrievalResult = withContext(dispatchers.io) {
        val totalStart = System.currentTimeMillis()

        // 1. Repair search index if dirty
        if (searchIndexStateDao?.isDirty() == true || memoryRepository.isIndexRebuildNeeded()) {
            try {
                memoryRepository.rebuildSearchIndex()
            } catch (e: Exception) {
                logger?.w("MemoryRetriever", "Index repair trigger failed: ${e.javaClass.simpleName}")
            }
        }

        // 2. Deterministic Query Analysis
        val analyzed = RetrievalQueryAnalyzer.analyze(query)

        // 3. Early exit on empty or purely generic noise queries
        if (analyzed.isGenericOrEmpty) {
            val totalLatency = System.currentTimeMillis() - totalStart
            logTelemetry(candidatesCount = 0, resultsCount = 0, latencyMs = totalLatency, signals = emptySet())
            return@withContext RetrievalResult(
                query = query.text,
                items = emptyList(),
                confidence = RetrievalConfidence.NONE,
                totalCandidatesFound = 0,
                executionTimeMs = totalLatency,
                metrics = RetrievalMetrics(totalLatencyMs = totalLatency)
            )
        }

        // 4. Candidate Generation (Bounded Multi-Query Union)
        val candStart = System.currentTimeMillis()
        val candidateItems = mutableListOf<MemoryItem>()

        // 4a. Content term sub-queries
        if (analyzed.contentTerms.isNotEmpty()) {
            for (term in analyzed.contentTerms.take(5)) {
                val termExpr = buildFtsMatchExpression(term)
                if (termExpr.isNotBlank()) {
                    candidateItems.addAll(executeFtsMatch(termExpr))
                }
            }
            val termFtsList = analyzed.contentTerms.map { buildFtsMatchExpression(it) }.filter { it.isNotBlank() }
            if (termFtsList.size > 1) {
                val orExpr = termFtsList.take(6).joinToString(" OR ")
                candidateItems.addAll(executeFtsMatch(orExpr))
            }
        }

        // 4b. Time token sub-queries (e.g. "十月*", "10月*")
        if (analyzed.timeTokens.isNotEmpty()) {
            for (token in analyzed.timeTokens.take(2)) {
                val timeExpr = buildFtsMatchExpression(token)
                if (timeExpr.isNotBlank()) {
                    candidateItems.addAll(executeFtsMatch(timeExpr))
                }
            }
        }

        // 4c. Source hint sub-query if candidate pool is still small
        if (candidateItems.isEmpty() && analyzed.sourceHint != null) {
            val srcExpr = buildFtsMatchExpression(analyzed.sourceHint.name)
            if (srcExpr.isNotBlank()) {
                candidateItems.addAll(executeFtsMatch(srcExpr))
            }
        }

        // 4d. Fallback: If FTS returned 0 candidates or was unavailable, use conservative LIKE repository search
        if (candidateItems.isEmpty() && analyzed.contentTerms.isNotEmpty()) {
            for (term in analyzed.contentTerms) {
                try {
                    val fallbackMatches = memoryRepository.searchMemories(term).first()
                    candidateItems.addAll(fallbackMatches)
                } catch (e: Exception) {
                    logger?.w("MemoryRetriever", "Fallback query failed: ${e.javaClass.simpleName}")
                }
            }
        }

        val candEnd = System.currentTimeMillis()
        val candLatency = candEnd - candStart

        // 5. Dedup and apply hard filters
        var distinctCandidates = candidateItems.distinctBy { it.id }
        if (query.options.filterSourceType != null) {
            distinctCandidates = distinctCandidates.filter { it.sourceType == query.options.filterSourceType }
        }

        // Bound candidate pool to policy limit
        val boundedCandidates = distinctCandidates.take(RetrievalPolicy.MAX_CANDIDATES)

        // 6. Deterministic Reranking
        val rerankStart = System.currentTimeMillis()
        val scoredItems = boundedCandidates.map { item ->
            val scoreResult = computeRelevance(item, analyzed)
            ScoredMemory(
                memory = item,
                score = scoreResult.score,
                signals = scoreResult.signals,
                eventSummary = scoreResult.eventSummary
            )
        }

        // Sort: primary by score DESC, secondary by capturedAt DESC
        val sortedScored = scoredItems.sortedWith(
            compareByDescending<ScoredMemory> { it.score }
                .thenByDescending { it.memory.capturedAt }
        )

        // 7. Context Budget & Top-K Truncation
        val effectiveTopK = (query.options.topK ?: RetrievalPolicy.DEFAULT_TOP_K)
            .coerceIn(1, RetrievalPolicy.MAX_TOP_K)

        val topKCandidates = sortedScored.take(effectiveTopK)

        val memoryContexts = mutableListOf<MemoryContext>()
        var totalCharsAccumulated = 0
        val allSignals = mutableSetOf<RetrievalSignal>()

        for (candidate in topKCandidates) {
            allSignals.addAll(candidate.signals)
            val excerpt = extractExcerpt(
                item = candidate.memory,
                contentTerms = analyzed.contentTerms,
                maxLength = RetrievalPolicy.MAX_EXCERPT_CHARS_PER_MEMORY
            )

            val remainingBudget = RetrievalPolicy.MAX_TOTAL_CONTEXT_CHARS - totalCharsAccumulated
            if (remainingBudget <= 0) break

            val finalExcerpt = if (excerpt.length > remainingBudget) {
                if (remainingBudget >= 100) {
                    excerpt.take(remainingBudget - 3) + "..."
                } else {
                    break
                }
            } else {
                excerpt
            }

            totalCharsAccumulated += finalExcerpt.length
            memoryContexts.add(
                MemoryContext(
                    memoryId = candidate.memory.id,
                    sourceType = candidate.memory.sourceType,
                    capturedAt = candidate.memory.capturedAt,
                    title = candidate.memory.title,
                    relevantExcerpt = finalExcerpt,
                    sourceUrl = candidate.memory.sourceUrl,
                    sourceExists = candidate.memory.sourceExists,
                    relevanceScore = candidate.score,
                    matchedSignals = candidate.signals,
                    eventSummary = candidate.eventSummary
                )
            )
        }

        val rerankEnd = System.currentTimeMillis()
        val rerankLatency = rerankEnd - rerankStart
        val totalLatency = rerankEnd - totalStart

        // 8. Compute Confidence
        val topScore = memoryContexts.firstOrNull()?.relevanceScore ?: 0.0
        val confidence = when {
            memoryContexts.isEmpty() || topScore <= 0.0 -> RetrievalConfidence.NONE
            topScore >= 80.0 -> RetrievalConfidence.HIGH
            topScore >= 40.0 -> RetrievalConfidence.MEDIUM
            else -> RetrievalConfidence.LOW
        }

        // 9. Structured Telemetry Logging (Zero Plaintext Leaks)
        logTelemetry(
            candidatesCount = boundedCandidates.size,
            resultsCount = memoryContexts.size,
            latencyMs = totalLatency,
            signals = allSignals
        )

        val metrics = RetrievalMetrics(
            candidateGenerationMs = candLatency,
            rerankMs = rerankLatency,
            totalLatencyMs = totalLatency,
            candidateCount = boundedCandidates.size,
            resultCount = memoryContexts.size,
            signalsDetected = allSignals
        )

        return@withContext RetrievalResult(
            query = query.text,
            items = memoryContexts,
            confidence = confidence,
            totalCandidatesFound = boundedCandidates.size,
            executionTimeMs = totalLatency,
            metrics = metrics
        )
    }

    private suspend fun executeFtsMatch(ftsExpr: String): List<MemoryItem> {
        if (searchDao == null || ftsExpr.isBlank()) return emptyList()
        return try {
            val entities = searchDao.searchMemoriesMatch(ftsExpr)
            entities.map { it.toDomain() }
        } catch (e: Exception) {
            logger?.w("MemoryRetriever", "FTS match failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun computeRelevance(item: MemoryItem, query: AnalyzedQuery): ScoreResult {
        var score = 0.0
        val signals = mutableSetOf<RetrievalSignal>()
        val titleLower = item.title?.lowercase(Locale.ROOT) ?: ""
        val rawLower = item.rawText?.lowercase(Locale.ROOT) ?: ""
        val normLower = item.normalizedText?.lowercase(Locale.ROOT) ?: ""
        val urlLower = item.sourceUrl?.lowercase(Locale.ROOT) ?: ""

        // 1. Title matching
        for (term in query.contentTerms) {
            val tLower = term.lowercase(Locale.ROOT)
            if (titleLower == tLower) {
                score += 100.0
                signals.add(RetrievalSignal.EXACT_TITLE)
            } else if (titleLower.contains(tLower)) {
                score += 70.0
                signals.add(RetrievalSignal.TITLE_MATCH)
            }
        }

        // 2. Body matching
        for (term in query.contentTerms) {
            val tLower = term.lowercase(Locale.ROOT)
            if (rawLower.contains(tLower) || normLower.contains(tLower)) {
                score += 50.0
                signals.add(RetrievalSignal.BODY_MATCH)
            }
        }

        // 3. Entity matching
        for (entity in item.entities) {
            val eLower = entity.lowercase(Locale.ROOT)
            for (term in query.contentTerms) {
                val tLower = term.lowercase(Locale.ROOT)
                if (eLower == tLower || eLower.contains(tLower) || tLower.contains(eLower)) {
                    score += 65.0
                    signals.add(RetrievalSignal.ENTITY_MATCH)
                }
            }
        }

        // 4. Event candidate matching
        var eventSummary: String? = null
        for (event in item.eventCandidates) {
            val evTitle = event.title.lowercase(Locale.ROOT)
            val evLoc = event.location?.lowercase(Locale.ROOT) ?: ""

            for (term in query.contentTerms) {
                val tLower = term.lowercase(Locale.ROOT)
                if (evTitle.contains(tLower)) {
                    score += 75.0
                    signals.add(RetrievalSignal.EVENT_TITLE_MATCH)
                }
                if (evLoc.contains(tLower)) {
                    score += 40.0
                    signals.add(RetrievalSignal.EVENT_LOCATION_MATCH)
                }
            }

            // Event date matching (Higher priority than capture date)
            if (query.timeHint != null) {
                val start = event.startTimestamp
                if (start in query.timeHint.startMillis..query.timeHint.endMillis) {
                    score += 85.0
                    signals.add(RetrievalSignal.EVENT_DATE_MATCH)
                }
            }

            if (eventSummary == null) {
                val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT).format(Date(event.startTimestamp))
                val locStr = event.location?.let { " @ $it" } ?: ""
                eventSummary = "${event.title} ($dateStr)$locStr"
            }
        }

        // 5. Capture date matching (Secondary to event date)
        if (query.timeHint != null && item.capturedAt in query.timeHint.startMillis..query.timeHint.endMillis) {
            score += 25.0
            signals.add(RetrievalSignal.CAPTURE_DATE_MATCH)
        }

        // 6. Source hint matching
        if (query.sourceHint != null && item.sourceType == query.sourceHint) {
            score += 30.0
            signals.add(RetrievalSignal.SOURCE_HINT_MATCH)
        }

        // 7. URL / Host matching
        for (term in query.contentTerms) {
            val tLower = term.lowercase(Locale.ROOT)
            if (urlLower.contains(tLower)) {
                score += 40.0
                signals.add(RetrievalSignal.URL_MATCH)
            }
        }

        // 8. Processing State adjustment
        if (item.processingState != ProcessingState.COMPLETED) {
            score *= 0.5
        }

        // 9. Recency weak tie-breaker (never overcomes semantic mismatch)
        score += (item.capturedAt.toDouble() / 1_000_000_000_000.0) * 0.001

        return ScoreResult(score, signals, eventSummary)
    }

    private fun extractExcerpt(item: MemoryItem, contentTerms: List<String>, maxLength: Int): String {
        val text = item.rawText ?: item.normalizedText ?: item.title ?: ""
        if (text.length <= maxLength) return text

        var bestIndex = -1
        for (term in contentTerms) {
            val idx = text.indexOf(term, ignoreCase = true)
            if (idx != -1 && (bestIndex == -1 || idx < bestIndex)) {
                bestIndex = idx
            }
        }

        if (bestIndex == -1) {
            val candidate = (item.title?.let { "$it\n" } ?: "") + text
            return if (candidate.length > maxLength) {
                candidate.take(maxLength - 3) + "..."
            } else {
                candidate
            }
        }

        val halfWindow = maxLength / 3
        val start = (bestIndex - halfWindow).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(text.length)
        val actualStart = if (end - start < maxLength) (end - maxLength).coerceAtLeast(0) else start

        val prefix = if (actualStart > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        val snippet = prefix + text.substring(actualStart, end).trim() + suffix
        return snippet.take(maxLength)
    }

    private fun buildFtsMatchExpression(input: String): String {
        val sanitized = input.replace(Regex("[\"*\\-:\\(\\)^~]"), " ").trim()
        if (sanitized.isBlank()) return ""

        val tokens = mutableListOf<String>()

        // Latin / Alphanumeric words with wildcard
        val words = Regex("[a-zA-Z0-9]+").findAll(sanitized).map { it.value.lowercase(Locale.ROOT) }.toList()
        for (w in words) {
            tokens.add("$w*")
        }

        // CJK characters: unigrams and bigrams
        val cjkChars = sanitized.filter { MemorySearchDocumentBuilder.isCjk(it) }
        if (cjkChars.isNotEmpty()) {
            if (cjkChars.length == 1) {
                tokens.add(cjkChars)
            } else if (cjkChars.length in 2..4) {
                tokens.add(cjkChars)
                for (i in 0 until cjkChars.length - 1) {
                    tokens.add(cjkChars.substring(i, i + 2))
                }
            } else {
                for (i in 0 until cjkChars.length - 1) {
                    tokens.add(cjkChars.substring(i, i + 2))
                }
            }
        }

        if (tokens.isEmpty()) return ""
        return tokens.distinct().joinToString(" OR ")
    }

    private fun logTelemetry(
        candidatesCount: Int,
        resultsCount: Int,
        latencyMs: Long,
        signals: Set<RetrievalSignal>
    ) {
        val signalStr = signals.map { it.name }.sorted().joinToString(",")
        logger?.i("MemoryRetriever", "retrieval candidates=$candidatesCount results=$resultsCount latencyMs=$latencyMs signals=[$signalStr]")
    }

    private data class ScoredMemory(
        val memory: MemoryItem,
        val score: Double,
        val signals: Set<RetrievalSignal>,
        val eventSummary: String?
    )

    private data class ScoreResult(
        val score: Double,
        val signals: Set<RetrievalSignal>,
        val eventSummary: String?
    )
}
