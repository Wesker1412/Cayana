package com.cayana.memory.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "memories_fts")
@Fts4
data class MemoryFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long = 0,
    val memoryId: String,
    val title: String,
    val rawText: String,
    val normalizedText: String,
    val sourceType: String,
    val sourceUrl: String,
    val host: String,
    val displayName: String,
    val searchTokens: String
)
