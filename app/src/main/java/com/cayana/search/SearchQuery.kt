package com.cayana.search

import com.cayana.source.SourceType

data class SearchQuery(
    val query: String,
    val filterSourceType: SourceType? = null,
    val limit: Int = 50
)
