package com.cayana.actions.notion.client

enum class NotionTargetType {
    PAGE,
    DATABASE
}

data class NotionTarget(
    val id: String,
    val title: String,
    val type: NotionTargetType,
    val icon: String? = null,
    val url: String? = null
)

data class NotionCreatedPage(
    val id: String,
    val url: String?,
    val title: String
)
