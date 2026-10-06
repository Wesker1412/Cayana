package com.cayana.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.search.MemorySearchEngine
import com.cayana.search.SearchFilterCategory
import com.cayana.search.SearchQuery
import com.cayana.search.SearchResult
import com.cayana.source.SourceExistence
import com.cayana.source.SourceExistenceValidator
import com.cayana.source.SourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val memoryRepository: MemoryRepository,
    private val searchEngine: MemorySearchEngine? = null,
    private val sourceValidator: SourceExistenceValidator? = null
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val filterCategory = MutableStateFlow(SearchFilterCategory.ALL)
    private val selectedDetailMemory = MutableStateFlow<MemoryItem?>(null)

    private val memoriesFlow = combine(searchQuery, filterCategory) { query, category ->
        Pair(query, category)
    }.flatMapLatest { (query, category) ->
        if (query.isBlank()) {
            memoryRepository.getAllMemories()
        } else {
            // Use search engine if available, otherwise fallback to repository flow
            if (searchEngine != null) {
                val results = searchEngine.search(
                    SearchQuery(query = query, filterCategory = category)
                )
                flowOf(results.map { it.memory })
            } else {
                memoryRepository.searchMemories(query)
            }
        }
    }.onEach { memories ->
        checkAndReconcileSources(memories)
    }

    private fun checkAndReconcileSources(memories: List<MemoryItem>) {
        val validator = sourceValidator ?: return
        viewModelScope.launch {
            memories.forEach { item ->
                if (item.sourceExists && item.sourceUri != null) {
                    val existence = validator.checkSourceExistence(item.sourceUri, item.sourceType)
                    if (existence is SourceExistence.Missing) {
                        memoryRepository.markSourceExists(item.id, false)
                    }
                }
            }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        memoriesFlow,
        memoryRepository.getMemoryCount(),
        searchQuery,
        filterCategory,
        selectedDetailMemory
    ) { memories, count, query, category, detailMemory ->
        val filtered = filterByCategory(memories, category)
        val searchResults = filtered.map { item ->
            SearchResult(memory = item)
        }
        HomeUiState(
            memories = filtered,
            searchResults = searchResults,
            totalCount = count,
            isLoading = false,
            searchQuery = query,
            filterCategory = category,
            selectedDetailMemory = detailMemory
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isLoading = true)
    )

    private fun filterByCategory(items: List<MemoryItem>, category: SearchFilterCategory): List<MemoryItem> {
        return when (category) {
            SearchFilterCategory.ALL -> items
            SearchFilterCategory.SCREENSHOTS -> items.filter { it.sourceType == SourceType.SCREENSHOT }
            SearchFilterCategory.PHOTOS -> items.filter { it.sourceType == SourceType.PHOTO }
            SearchFilterCategory.RECORDINGS -> items.filter { it.sourceType == SourceType.RECORDING }
            SearchFilterCategory.SHARED -> items.filter { it.sourceType.isShared }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onFilterCategoryChanged(category: SearchFilterCategory) {
        filterCategory.value = category
    }

    fun onSelectMemoryForDetail(item: MemoryItem?) {
        selectedDetailMemory.value = item
    }

    fun addSampleMemory(item: MemoryItem): Job =
        viewModelScope.launch {
            memoryRepository.saveMemory(item)
        }

    fun deleteMemory(id: String): Job =
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
            if (selectedDetailMemory.value?.id == id) {
                selectedDetailMemory.value = null
            }
        }
}
