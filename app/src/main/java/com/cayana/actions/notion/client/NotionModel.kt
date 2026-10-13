package com.cayana.actions.notion.client

enum class NotionTargetType {
    PAGE,
    DATABASE,
    DATA_SOURCE
}

data class NotionTarget(
    val id: String,
    val title: String,
    val type: NotionTargetType,
    val icon: String? = null,
    val url: String? = null,
    val isSelectable: Boolean = true
)

data class NotionSearchResponse(
    val targets: List<NotionTarget>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

data class NotionCreatedPage(
    val id: String,
    val url: String?,
    val title: String
)
