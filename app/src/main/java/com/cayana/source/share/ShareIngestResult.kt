package com.cayana.source.share

import com.cayana.memory.model.MemoryItem

sealed interface ShareIngestResult {
    data class Success(val memories: List<MemoryItem>, val message: String = "已記住") : ShareIngestResult
    data class PartialSuccess(val memories: List<MemoryItem>, val message: String = "已記住基本資訊，但原始內容無法讀取。") : ShareIngestResult
    data class Duplicate(val existingIds: List<String>) : ShareIngestResult
    data class Ignored(val reason: String) : ShareIngestResult
}
