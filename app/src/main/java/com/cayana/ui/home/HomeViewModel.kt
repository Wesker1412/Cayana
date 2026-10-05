package com.cayana.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.cayana.source.SourceExistenceValidator
import kotlinx.coroutines.flow.onEach

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val memoryRepository: MemoryRepository,
    private val sourceValidator: SourceExistenceValidator? = null
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    private val memoriesFlow = searchQuery.flatMapLatest { query ->
        if (query.isBlank()) {
            memoryRepository.getAllMemories()
        } else {
            memoryRepository.searchMemories(query)
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
                    if (existence is com.cayana.source.SourceExistence.Missing) {
                        memoryRepository.markSourceExists(item.id, false)
                    }
                }
            }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        memoriesFlow,
        memoryRepository.getMemoryCount(),
        searchQuery
    ) { memories, count, query ->
        HomeUiState(
            memories = memories,
            totalCount = count,
            isLoading = false,
            searchQuery = query
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isLoading = true)
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun addSampleMemory(item: MemoryItem) {
        viewModelScope.launch {
            memoryRepository.saveMemory(item)
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
        }
    }
}
