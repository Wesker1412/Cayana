package com.cayana.test

import com.cayana.core.common.Result
import com.cayana.processing.OcrBlock
import com.cayana.processing.OcrEngine
import com.cayana.processing.OcrResult

class FakeOcrEngine(
    var simulatedResult: Result<OcrResult> = Result.Success(OcrResult(fullText = "Sample OCR Text"))
) : OcrEngine {

    override val isReady: Boolean = true

    override suspend fun extractText(uri: String): Result<String> {
        return when (val res = simulatedResult) {
            is Result.Success -> Result.Success(res.data.fullText)
            is Result.Error -> Result.Error(res.exception)
            Result.Loading -> Result.Loading
        }
    }

    override suspend fun processImage(uri: String): Result<OcrResult> {
        return simulatedResult
    }
}
