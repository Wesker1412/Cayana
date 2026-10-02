package com.cayana.source

data class SourceItem(
    val id: String,
    val sourceType: SourceType,
    val uri: String,
    val mimeType: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val contentLength: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)
