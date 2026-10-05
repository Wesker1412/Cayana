package com.cayana.test

import android.content.Context
import com.cayana.processing.SpeechToTextEngine
import com.cayana.processing.SttChunkResult
import com.cayana.processing.SttSegment

class FakeSpeechToTextEngine(
    override val engineName: String = "FakeSttEngine",
    @Volatile override var isModelAvailable: Boolean = true
) : SpeechToTextEngine {

    var audioDurationMs: Long = 60_000L
    var simulateFailureAtChunk: Int? = null
    val chunksTranscribed = mutableListOf<Int>()

    override suspend fun getAudioDurationMs(context: Context, audioUri: String): Long {
        return audioDurationMs
    }

    override suspend fun transcribeChunk(
        context: Context,
        audioUri: String,
        chunkIndex: Int,
        startMs: Long,
        durationMs: Long
    ): SttChunkResult {
        if (!isModelAvailable) {
            return SttChunkResult.ModelUnavailable
        }

        chunksTranscribed.add(chunkIndex)

        if (simulateFailureAtChunk == chunkIndex) {
            return SttChunkResult.Failure(
                RuntimeException("Simulated failure at chunk $chunkIndex"),
                isRetryable = true
            )
        }

        val text = "Chunk $chunkIndex text"
        return SttChunkResult.Success(
            chunkIndex = chunkIndex,
            text = text,
            segments = listOf(
                SttSegment(startMs = startMs, endMs = startMs + durationMs, text = text)
            )
        )
    }
}
