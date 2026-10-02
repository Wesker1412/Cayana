package com.cayana.processing

import com.cayana.core.common.Result

data class OcrBlock(
    val text: String,
    val lines: List<String> = emptyList()
)

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrBlock> = emptyList()
)

interface OcrEngine {
    val isReady: Boolean
    suspend fun extractText(uri: String): Result<String>
    suspend fun processImage(uri: String): Result<OcrResult> {
        return when (val res = extractText(uri)) {
            is Result.Success -> Result.Success(OcrResult(fullText = res.data))
            is Result.Error -> Result.Error(res.exception)
            Result.Loading -> Result.Loading
        }
    }
}

/**
 * Stub implementation.
 */
class StubOcrEngine : OcrEngine {
    override val isReady: Boolean = false

    override suspend fun extractText(uri: String): Result<String> {
        return Result.Error(UnsupportedOperationException("OCR is not enabled"))
    }
}
