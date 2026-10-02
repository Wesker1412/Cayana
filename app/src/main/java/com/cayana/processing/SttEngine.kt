package com.cayana.processing

import com.cayana.core.common.Result

interface SttEngine {
    val isReady: Boolean
    suspend fun transcribeAudio(uri: String): Result<String>
}

/**
 * Stub implementation for Stage 0.
 * Real STT (e.g. sherpa-onnx / whisper) will be plugged in during subsequent stages.
 */
class StubSttEngine : SttEngine {
    override val isReady: Boolean = false

    override suspend fun transcribeAudio(uri: String): Result<String> {
        return Result.Error(UnsupportedOperationException("STT is not enabled in Stage 0"))
    }
}
