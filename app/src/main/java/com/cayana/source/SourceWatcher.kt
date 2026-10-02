package com.cayana.source

import kotlinx.coroutines.flow.Flow

interface SourceWatcher {
    val sourceType: SourceType
    fun isAvailable(): Boolean
    fun observeNewItems(): Flow<SourceItem>
}
