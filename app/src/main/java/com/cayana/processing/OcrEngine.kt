package com.cayana.processing

import com.cayana.core.common.Result

interface OcrEngine {
    val isReady: Boolean
    suspend fun extractText(uri: String): Result<String>
}

/**
 * Stub implementation for Stage 0.
 * Real OCR (e.g. ML Kit / offline OCR) will be plugged in during subsequent stages.
 */
class StubOcrEngine : OcrEngine {
    override val isReady: Boolean = false

    override suspend fun extractText(uri: String): Result<String> {
        return Result.Error(UnsupportedOperationException("OCR is not enabled in Stage 0"))
    }
}
