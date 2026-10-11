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
import com.cayana.search.data.SearchIndexVersionStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Production implementation of MemoryRetriever for Stage 7A.
 * Fully deterministic, strictly resource-bounded, local-first retrieval layer.
 * Reuses existing Room FTS4 index without requiring a secondary database.
 */
class DefaultMemoryRetriever(
    private val memoryRepository: MemoryRepository,
    private val searchDao: SearchDao? = null,
    private val searchIndexStateDao: SearchIndexStateDao? = null,
    private val searchIndexVersionStorage: SearchIndexVersionStorage? = null,
    private val dispatchers: CoroutineDispatchers = AppDispatchers(),
    private val logger: CayanaLogger? = null
) : MemoryRetriever {

    override suspend fun retrieve(query: RetrievalQuery): RetrievalResult = withContext(dispatchers.io) {
        val totalStart = System.currentTimeMillis()

        // 1. Repair search index if dirty or if persistent format version upgrade is needed
        val currentVersion = searchIndexVersionStorage?.getIndexFormatVersion() ?: RetrievalPolicy.CURRENT_INDEX_FORMAT_VERSION
        val isUpgradeNeeded = currentVersion < RetrievalPolicy.CURRENT_INDEX_FORMAT_VERSION
        val isDirty = searchIndexStateDao?.isDirty() == true || memoryRepository.isIndexRebuildNeeded()

        if (isDirty || isUpgradeNeeded) {
            try {
                memoryRepository.rebuildSearchIndex()
                searchIndexVersionStorage?.setIndexFormatVersion(RetrievalPolicy.CURRENT_INDEX_FORMAT_VERSION)
            } catch (e: Exception) {
                logger?.w("MemoryRetriever", "Index repair / upgrade trigger failed: ${e.javaClass.simpleName}")
            }
        }

        // 2. Deterministic Query Analysis (bounded input length, term count)
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

        // 4. Candidate Generation (Bounded Multi-Query Union with Tier Prioritization)
        val candStart = System.currentTimeMillis()
        val tier1SpecificCandidates = mutableListOf<MemoryItem>()
        val tier2TermCandidates = mutableListOf<MemoryItem>()
        val tier3HintCandidates = mutableListOf<MemoryItem>()
        val tier4SecondaryCandidates = mutableListOf<MemoryItem>()

        var subQueryCount = 0

        // Tier 1: Exact / Prefix Title Matches & Compound Multi-Token FTS Queries (High Precision)
        for (term in analyzed.contentTerms.take(2)) {
            if (subQueryCount >= RetrievalPolicy.MAX_MULTI_QUERIES) break
            val termExpr = buildFtsMatchExpression(term)
            if (termExpr.isNotBlank()) {
                val titleMatches = executeFtsMatch(termExpr, term = term, limit = 10)
                val prioritized = titleMatches.filter { it.title?.contains(term, ignoreCase = true) == true }
                if (prioritized.isNotEmpty()) {
                    subQueryCount++
                    tier1SpecificCandidates.addAll(prioritized)
                }
            }
        }
        if (analyzed.contentTerms.size > 1 && subQueryCount < RetrievalPolicy.MAX_MULTI_QUERIES) {
            val andExpr = buildFtsAndExpression(analyzed.contentTerms.take(4))
            if (andExpr.isNotBlank()) {
                subQueryCount++
                tier1SpecificCandidates.addAll(executeFtsMatch(andExpr, limit = RetrievalPolicy.MAX_SQL_CANDIDATE_LIMIT_PER_QUERY))
            }
        }
        val longestTerm = analyzed.contentTerms.maxByOrNull { it.length }
        if (longestTerm != null && longestTerm.length >= 4 && subQueryCount < RetrievalPolicy.MAX_MULTI_QUERIES) {
            val expr = buildFtsMatchExpression(longestTerm)
            if (expr.isNotBlank()) {
                subQueryCount++
                tier1SpecificCandidates.addAll(executeFtsMatch(expr, term = longestTerm, limit = RetrievalPolicy.MAX_SQL_CANDIDATE_LIMIT_PER_QUERY))
            }
        }

        // Tier 2: Individual Content Terms
        for (term in analyzed.contentTerms.take(4)) {
            if (subQueryCount >= RetrievalPolicy.MAX_MULTI_QUERIES) break
            val termExpr = buildFtsMatchExpression(term)
            if (termExpr.isNotBlank()) {
                subQueryCount++
                tier2TermCandidates.addAll(executeFtsMatch(termExpr, term = term, limit = 15))
            }
        }

        // Tier 3: Time Token & Source Hint Sub-Queries
        if (analyzed.timeTokens.isNotEmpty() && subQueryCount < RetrievalPolicy.MAX_MULTI_QUERIES) {
            for (token in analyzed.timeTokens.take(2)) {
                if (subQueryCount >= RetrievalPolicy.MAX_MULTI_QUERIES) break
                val timeExpr = buildFtsMatchExpression(token)
                if (timeExpr.isNotBlank()) {
                    subQueryCount++
                    tier3HintCandidates.addAll(executeFtsMatch(timeExpr, limit = 10))
                }
            }
        }
        if (analyzed.sourceHint != null && subQueryCount < RetrievalPolicy.MAX_MULTI_QUERIES) {
            val srcExpr = buildFtsMatchExpression(analyzed.sourceHint.name)
            if (srcExpr.isNotBlank()) {
                subQueryCount++
                tier3HintCandidates.addAll(executeFtsMatch(srcExpr, limit = 10))
            }
        }

        // Tier 4: Loose OR Secondary Recall (Only used if candidates pool has headroom)
        val initialFound = tier1SpecificCandidates.size + tier2TermCandidates.size
        if (initialFound < RetrievalPolicy.MAX_CANDIDATES && analyzed.contentTerms.size > 1 && subQueryCount < RetrievalPolicy.MAX_MULTI_QUERIES) {
            val termFtsList = analyzed.contentTerms.map { buildFtsMatchExpression(it) }.filter { it.isNotBlank() }
            if (termFtsList.size > 1) {
                val orExpr = termFtsList.take(4).joinToString(" OR ")
                subQueryCount++
                tier4SecondaryCandidates.addAll(executeFtsMatch(orExpr, limit = 10))
            }
        }

        // Tier 5: Fallback if FTS was unavailable or returned 0 candidates across all tiers
        val totalFtsCount = tier1SpecificCandidates.size + tier2TermCandidates.size + tier3HintCandidates.size + tier4SecondaryCandidates.size
        if (totalFtsCount == 0 && analyzed.contentTerms.isNotEmpty()) {
            for (term in analyzed.contentTerms.take(3)) {
                try {
                    val fallbackMatches = memoryRepository.searchMemoriesBounded(term, limit = 10)
                    tier2TermCandidates.addAll(fallbackMatches)
                } catch (e: Exception) {
                    logger?.w("MemoryRetriever", "Fallback query failed: ${e.javaClass.simpleName}")
                }
            }
        }

        // Bounded Union & Dedup with Tier Priority
        val seenIds = mutableSetOf<String>()
        val distinctCandidates = mutableListOf<MemoryItem>()

        fun addCandidates(list: List<MemoryItem>, quota: Int) {
            var added = 0
            for (item in list) {
                if (distinctCandidates.size >= RetrievalPolicy.MAX_CANDIDATES) break
                if (added >= quota) break
                if (seenIds.add(item.id)) {
                    distinctCandidates.add(item)
                    added++
                }
            }
        }

        addCandidates(tier1SpecificCandidates, quota = 25)
        addCandidates(tier2TermCandidates, quota = 25)
        addCandidates(tier3HintCandidates, quota = 15)
        addCandidates(tier4SecondaryCandidates, quota = 10)

        val candEnd = System.currentTimeMillis()
        val candLatency = candEnd - candStart

        // Filter by source type if explicitly requested
        var filteredCandidates = distinctCandidates.toList()
        if (query.options.filterSourceType != null) {
            filteredCandidates = filteredCandidates.filter { it.sourceType == query.options.filterSourceType }
        }

        val boundedCandidates = filteredCandidates.take(RetrievalPolicy.MAX_CANDIDATES)

        // 5. Deterministic Reranking
        val rerankStart = System.currentTimeMillis()
        val scoredItems = boundedCandidates.map { item ->
            val scoreResult = computeRelevance(item, analyzed)
            ScoredMemory(
                memory = item,
                score = scoreResult.score,
                signals = scoreResult.signals,
                thematicSignals = scoreResult.thematicSignals,
                eventSummary = scoreResult.eventSummary
            )
        }

        // Primary sort: score DESC, secondary sort: capturedAt DESC
        val sortedScored = scoredItems.sortedWith(
            compareByDescending<ScoredMemory> { it.score }
                .thenByDescending { it.memory.capturedAt }
        )

        // 6. Context Budget & Top-K Truncation (Accounting for whole serialized MemoryContext)
        val effectiveTopK = (query.options.topK ?: RetrievalPolicy.DEFAULT_TOP_K)
            .coerceIn(1, RetrievalPolicy.MAX_TOP_K)

        val topKCandidates = sortedScored.take(effectiveTopK)

        val memoryContexts = mutableListOf<MemoryContext>()
        var totalCharsAccumulated = 0
        val allSignals = mutableSetOf<RetrievalSignal>()

        for (candidate in topKCandidates) {
            val safeUrl = sanitizeSourceUrl(candidate.memory.sourceUrl)
            val safeTitle = candidate.memory.title?.take(200)
            val safeEventSummary = candidate.eventSummary?.take(200)

            val metadataChars = (safeTitle?.length ?: 0) + (safeUrl?.length ?: 0) + (safeEventSummary?.length ?: 0)
            val remainingBudget = RetrievalPolicy.MAX_TOTAL_CONTEXT_CHARS - totalCharsAccumulated

            // If remaining budget cannot fit metadata plus minimal excerpt, break
            if (remainingBudget < metadataChars + 50) break

            val maxAllowedExcerpt = (remainingBudget - metadataChars).coerceAtMost(RetrievalPolicy.MAX_EXCERPT_CHARS_PER_MEMORY)
            val excerpt = extractExcerpt(
                item = candidate.memory,
                contentTerms = analyzed.contentTerms,
                maxLength = maxAllowedExcerpt
            )

            val itemTotalChars = excerpt.length + metadataChars
            totalCharsAccumulated += itemTotalChars
            allSignals.addAll(candidate.signals)

            memoryContexts.add(
                MemoryContext(
                    memoryId = candidate.memory.id,
                    sourceType = candidate.memory.sourceType,
                    capturedAt = candidate.memory.capturedAt,
                    title = safeTitle,
                    relevantExcerpt = excerpt,
                    sourceUrl = safeUrl,
                    sourceExists = candidate.memory.sourceExists,
                    relevanceScore = candidate.score,
                    matchedSignals = candidate.signals,
                    eventSummary = safeEventSummary
                )
            )
        }

        val rerankEnd = System.currentTimeMillis()
        val rerankLatency = rerankEnd - rerankStart
        val totalLatency = rerankEnd - totalStart

        // 7. Compute Confidence (Requires thematic evidence when content terms exist)
        val topItem = sortedScored.firstOrNull()
        val topScore = topItem?.score ?: 0.0
        val topThematicSignals = topItem?.thematicSignals ?: emptySet()
        val hasThematicEvidence = topThematicSignals.isNotEmpty()

        val confidence = when {
            memoryContexts.isEmpty() || topScore <= 0.0 -> RetrievalConfidence.NONE
            // Date / source only matches without topic evidence cannot be HIGH
            analyzed.contentTerms.isNotEmpty() && !hasThematicEvidence -> {
                if (topScore >= 25.0) RetrievalConfidence.LOW else RetrievalConfidence.NONE
            }
            topScore >= 80.0 && hasThematicEvidence -> RetrievalConfidence.HIGH
            topScore >= 40.0 -> RetrievalConfidence.MEDIUM
            else -> RetrievalConfidence.LOW
        }

        // 8. Structured Telemetry Logging (Zero Plaintext Leaks)
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

    private suspend fun executeFtsMatch(
        ftsExpr: String,
        term: String = "",
        limit: Int = RetrievalPolicy.MAX_SQL_CANDIDATE_LIMIT_PER_QUERY
    ): List<MemoryItem> {
        if (searchDao == null || ftsExpr.isBlank()) return emptyList()
        val clampedExpr = ftsExpr.take(RetrievalPolicy.MAX_FTS_EXPRESSION_CHARS)
        return try {
            val entities = searchDao.searchMemoriesMatchBounded(clampedExpr, limit = limit, term = term)
            entities.map { it.toDomain() }
        } catch (e: Exception) {
            logger?.w("MemoryRetriever", "FTS match failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun computeRelevance(item: MemoryItem, query: AnalyzedQuery): ScoreResult {
        var score = 0.0
        val signals = mutableSetOf<RetrievalSignal>()
        val thematicSignals = mutableSetOf<RetrievalSignal>()

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
                thematicSignals.add(RetrievalSignal.EXACT_TITLE)
            } else if (titleLower.contains(tLower)) {
                score += 70.0
                signals.add(RetrievalSignal.TITLE_MATCH)
                thematicSignals.add(RetrievalSignal.TITLE_MATCH)
            }
        }

        // 2. Body matching
        for (term in query.contentTerms) {
            val tLower = term.lowercase(Locale.ROOT)
            if (rawLower.contains(tLower) || normLower.contains(tLower)) {
                score += 50.0
                signals.add(RetrievalSignal.BODY_MATCH)
                thematicSignals.add(RetrievalSignal.BODY_MATCH)
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
                    thematicSignals.add(RetrievalSignal.ENTITY_MATCH)
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
                    thematicSignals.add(RetrievalSignal.EVENT_TITLE_MATCH)
                }
                if (evLoc.contains(tLower)) {
                    score += 40.0
                    signals.add(RetrievalSignal.EVENT_LOCATION_MATCH)
                    thematicSignals.add(RetrievalSignal.EVENT_LOCATION_MATCH)
                }
            }

            // Event date matching (Amplifier when thematic evidence exists, capped otherwise)
            if (query.timeHint != null) {
                val start = event.startTimestamp
                if (start in query.timeHint.startMillis..query.timeHint.endMillis) {
                    val dateBoost = if (thematicSignals.isNotEmpty()) 60.0 else 25.0
                    score += dateBoost
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
            val capBoost = if (thematicSignals.isNotEmpty()) 25.0 else 10.0
            score += capBoost
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
                thematicSignals.add(RetrievalSignal.URL_MATCH)
            }
        }

        // 8. Processing State adjustment
        if (item.processingState != ProcessingState.COMPLETED) {
            score *= 0.5
        }

        // 9. Recency weak tie-breaker (never overcomes semantic mismatch)
        score += (item.capturedAt.toDouble() / 1_000_000_000_000.0) * 0.001

        return ScoreResult(score, signals, thematicSignals, eventSummary)
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

    private fun buildFtsAndExpression(terms: List<String>): String {
        val tokens = terms.map { buildFtsMatchExpression(it) }.filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ").take(RetrievalPolicy.MAX_FTS_EXPRESSION_CHARS)
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
        return tokens.distinct().joinToString(" OR ").take(RetrievalPolicy.MAX_FTS_EXPRESSION_CHARS)
    }

    private fun sanitizeSourceUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: "https"
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            val path = uri.path ?: ""
            // Strict transmission boundary: query, fragment, and userinfo are stripped by default.
            // Never rely on sensitive key blacklists. Original sourceUrl is preserved in Room.
            "$scheme://$host$port$path".take(300)
        } catch (_: Exception) {
            url.substringBefore('?').substringBefore('#').take(300)
        }
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
        val thematicSignals: Set<RetrievalSignal>,
        val eventSummary: String?
    )

    private data class ScoreResult(
        val score: Double,
        val signals: Set<RetrievalSignal>,
        val thematicSignals: Set<RetrievalSignal>,
        val eventSummary: String?
    )
}
