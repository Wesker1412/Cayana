package com.cayana.ui.home

import com.cayana.memory.model.MemoryItem
import com.cayana.search.SearchFilterCategory
import com.cayana.search.SearchResult

data class HomeUiState(
    val memories: List<MemoryItem> = emptyList(),
    val searchResults: List<SearchResult> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val filterCategory: SearchFilterCategory = SearchFilterCategory.ALL,
    val selectedDetailMemory: MemoryItem? = null
)
