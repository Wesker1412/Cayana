package com.cayana.search

import com.cayana.memory.model.MemoryItem

data class SearchResult(
    val memory: MemoryItem,
    val matchedSnippet: String? = null
)
