package com.cayana.search

import com.cayana.memory.repository.MemoryRepository
import kotlinx.coroutines.flow.first

interface MemorySearchEngine {
    suspend fun search(query: SearchQuery): List<SearchResult>
}

class DefaultMemorySearchEngine(
    private val memoryRepository: MemoryRepository
) : MemorySearchEngine {

    override suspend fun search(query: SearchQuery): List<SearchResult> {
        if (query.query.isBlank()) return emptyList()
        val results = memoryRepository.searchMemories(query.query).first()
        return results
            .filter { item ->
                query.filterSourceType == null || item.sourceType == query.filterSourceType
            }
            .take(query.limit)
            .map { item ->
                SearchResult(
                    memory = item,
                    matchedSnippet = item.rawText?.take(100)
                )
            }
    }
}
