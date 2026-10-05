package com.cayana.processing

import android.content.Context

data class SttSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

sealed interface SttChunkResult {
    data class Success(
        val chunkIndex: Int,
        val text: String,
        val segments: List<SttSegment> = emptyList()
    ) : SttChunkResult

    data class Failure(
        val error: Throwable,
        val isRetryable: Boolean = true
    ) : SttChunkResult

    data object ModelUnavailable : SttChunkResult
}

/**
 * Common abstraction for Speech-To-Text engines.
 * Production implementations must perform local on-device inference without cloud fallback.
 */
interface SpeechToTextEngine {
    val engineName: String
    val isModelAvailable: Boolean

    suspend fun getAudioDurationMs(context: Context, audioUri: String): Long

    suspend fun transcribeChunk(
        context: Context,
        audioUri: String,
        chunkIndex: Int,
        startMs: Long,
        durationMs: Long
    ): SttChunkResult
}
