package com.cayana.actions.tasks.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_actions",
    indices = [
        Index(value = ["sourceMemoryId", "actionType", "taskListId"], unique = true),
        Index(value = ["sourceMemoryId"]),
        Index(value = ["status"])
    ]
)
data class TaskActionEntity(
    @PrimaryKey
    val id: String,
    val sourceMemoryId: String,
    val actionType: String,
    val taskListId: String,
    val taskListTitle: String? = null,
    val destinationTaskId: String? = null,
    val status: String,
    val title: String,
    val originalDueAt: Long? = null,
    val googleDue: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastError: String? = null
)
