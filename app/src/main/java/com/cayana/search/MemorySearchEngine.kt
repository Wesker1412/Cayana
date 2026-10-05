package com.cayana.search

import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.search.data.SearchDao
import com.cayana.source.SourceType
import kotlinx.coroutines.flow.first
import java.util.Locale

interface MemorySearchEngine {
    suspend fun search(query: SearchQuery): List<SearchResult>
}

class DefaultMemorySearchEngine(
    private val memoryRepository: MemoryRepository,
    private val searchDao: SearchDao? = null
) : MemorySearchEngine {

    override suspend fun search(query: SearchQuery): List<SearchResult> {
        val rawInput = query.query.trim()
        if (rawInput.isBlank()) return emptyList()

        // 1. Sanitize query and generate FTS tokens
        val ftsExpression = buildFtsMatchExpression(rawInput)

        // 2. Perform FTS query
        val candidateItems = mutableListOf<MemoryItem>()
        if (searchDao != null && ftsExpression.isNotBlank()) {
            try {
                val ftsMatches = searchDao.searchMemoriesMatch(ftsExpression)
                candidateItems.addAll(ftsMatches.map { it.toDomain() })
            } catch (e: Exception) {
                CayanaLogger.w("SearchEngine", "FTS match query failed: ${e.message}")
            }
        }

        // 3. Fallback: if FTS returned nothing or was unavailable, try conservative LIKE search
        if (candidateItems.isEmpty()) {
            try {
                val fallbackMatches = memoryRepository.searchMemories(rawInput).first()
                candidateItems.addAll(fallbackMatches)
            } catch (e: Exception) {
                CayanaLogger.w("SearchEngine", "Fallback search failed: ${e.message}")
            }
        }

        // 4. Dedup candidates by ID
        val distinctCandidates = candidateItems.distinctBy { it.id }

        // 5. Apply filters
        val filtered = distinctCandidates.filter { item ->
            matchesFilter(item, query)
        }

        // 6. Rank results
        val ranked = rankMemories(filtered, rawInput)

        // 7. Map to SearchResult with snippets
        return ranked.take(query.limit).map { item ->
            SearchResult(
                memory = item,
                matchedSnippet = extractSnippet(item, rawInput)
            )
        }
    }

    private fun matchesFilter(item: MemoryItem, query: SearchQuery): Boolean {
        if (query.filterSourceType != null && item.sourceType != query.filterSourceType) {
            return false
        }
        return when (query.filterCategory) {
            SearchFilterCategory.ALL -> true
            SearchFilterCategory.SCREENSHOTS -> item.sourceType == SourceType.SCREENSHOT
            SearchFilterCategory.PHOTOS -> item.sourceType == SourceType.PHOTO
            SearchFilterCategory.RECORDINGS -> item.sourceType == SourceType.RECORDING
            SearchFilterCategory.SHARED -> item.sourceType.isShared
        }
    }

    fun buildFtsMatchExpression(input: String): String {
        val sanitized = sanitizeQuery(input)
        if (sanitized.isBlank()) return ""

        val tokens = mutableListOf<String>()

        // Extract Latin / alphanumeric tokens
        val words = Regex("[a-zA-Z0-9]+").findAll(sanitized).map { it.value.lowercase(Locale.ROOT) }.toList()
        for (w in words) {
            tokens.add("$w*")
        }

        // Extract CJK sequences
        val cjkChars = sanitized.filter { MemorySearchDocumentBuilder.isCjk(it) }
        if (cjkChars.isNotEmpty()) {
            if (cjkChars.length == 1) {
                tokens.add(cjkChars)
            } else {
                // Generate bigrams
                for (i in 0 until cjkChars.length - 1) {
                    tokens.add(cjkChars.substring(i, i + 2))
                }
            }
        }

        return tokens.joinToString(" ")
    }

    fun sanitizeQuery(input: String): String {
        // Strip special FTS characters that could cause syntax errors: " * - : ( ) ^
        return input.replace(Regex("[\"*\\-:\\(\\)^~]"), " ").trim()
    }

    private fun rankMemories(items: List<MemoryItem>, query: String): List<MemoryItem> {
        val lowerQuery = query.lowercase(Locale.ROOT)
        return items.sortedWith(
            compareByDescending<MemoryItem> { item ->
                computeRelevanceScore(item, lowerQuery)
            }.thenByDescending { it.capturedAt }
        )
    }

    private fun computeRelevanceScore(item: MemoryItem, lowerQuery: String): Int {
        var score = 0
        val title = item.title?.lowercase(Locale.ROOT) ?: ""
        val rawText = item.rawText?.lowercase(Locale.ROOT) ?: ""
        val url = item.sourceUrl?.lowercase(Locale.ROOT) ?: ""

        // Exact title match
        if (title == lowerQuery) {
            score += 100
        } else if (title.contains(lowerQuery)) {
            score += 80
        }

        // Host / URL match
        if (url.contains(lowerQuery)) {
            score += 60
        }

        // Content match
        if (rawText.contains(lowerQuery)) {
            score += 40
        }

        // Source type display name match
        if (item.sourceType.displayName.lowercase(Locale.ROOT).contains(lowerQuery)) {
            score += 20
        }

        return score
    }

    private fun extractSnippet(item: MemoryItem, query: String): String? {
        val text = item.rawText ?: item.title ?: return null
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerQuery = query.lowercase(Locale.ROOT)
        val index = lowerText.indexOf(lowerQuery)
        if (index == -1) return text.take(120)

        val start = (index - 30).coerceAtLeast(0)
        val end = (index + lowerQuery.length + 70).coerceAtMost(text.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return prefix + text.substring(start, end).trim() + suffix
    }
}
