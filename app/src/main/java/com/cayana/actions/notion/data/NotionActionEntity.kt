package com.cayana.actions.notion.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notion_actions",
    indices = [
        Index(value = ["sourceMemoryId", "actionType", "targetId"], unique = true),
        Index(value = ["sourceMemoryId"]),
        Index(value = ["status"])
    ]
)
data class NotionActionEntity(
    @PrimaryKey
    val id: String,
    val sourceMemoryId: String,
    val actionType: String,
    val targetId: String,
    val targetTitle: String? = null,
    val targetType: String,
    val destinationPageId: String? = null,
    val destinationPageUrl: String? = null,
    val status: String,
    val title: String,
    val content: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastError: String? = null
)
