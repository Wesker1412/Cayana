package com.cayana.ui.home

import com.cayana.memory.model.MemoryItem

data class HomeUiState(
    val memories: List<MemoryItem> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val searchQuery: String = ""
)
