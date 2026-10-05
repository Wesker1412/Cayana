package com.cayana.search

import com.cayana.source.SourceType

enum class SearchFilterCategory(val label: String) {
    ALL("All"),
    SCREENSHOTS("Screenshots"),
    PHOTOS("Photos"),
    RECORDINGS("Recordings"),
    SHARED("Shared")
}

data class SearchQuery(
    val query: String,
    val filterCategory: SearchFilterCategory = SearchFilterCategory.ALL,
    val filterSourceType: SourceType? = null,
    val limit: Int = 50
)
