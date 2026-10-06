package com.cayana.search.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_index_state")
data class SearchIndexStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val isDirty: Boolean = false,
    val pendingRepairs: Int = 0,
    val lastUpdated: Long = 0L
)
