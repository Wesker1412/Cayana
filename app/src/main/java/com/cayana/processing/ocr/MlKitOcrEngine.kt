package com.cayana.processing.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.processing.OcrBlock
import com.cayana.processing.OcrEngine
import com.cayana.processing.OcrResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device local OCR engine powered by ML Kit Text Recognition v2 (Chinese bundled model).
 * Supports Traditional Chinese, Simplified Chinese, and Latin/English without requiring network.
 */
class MlKitOcrEngine(
    private val context: Context,
    private val dispatchers: CoroutineDispatchers = AppDispatchers()
) : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override val isReady: Boolean = true

    override suspend fun extractText(uri: String): Result<String> {
        return when (val result = processImage(uri)) {
            is Result.Success -> Result.Success(result.data.fullText)
            is Result.Error -> Result.Error(result.exception)
            Result.Loading -> Result.Loading
        }
    }

    override suspend fun processImage(uri: String): Result<OcrResult> = withContext(dispatchers.io) {
        try {
            val parsedUri = Uri.parse(uri)
            val inputImage = InputImage.fromFilePath(context, parsedUri)
            val visionText = recognizer.process(inputImage).await()
            val blocks = visionText.textBlocks.map { block ->
                OcrBlock(
                    text = block.text,
                    lines = block.lines.map { it.text }
                )
            }
            Result.Success(OcrResult(fullText = visionText.text, blocks = blocks))
        } catch (e: Exception) {
            CayanaLogger.w("MlKitOcrEngine", "Failed to process image: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    suspend fun processBitmap(bitmap: Bitmap): Result<OcrResult> = withContext(dispatchers.io) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            val blocks = visionText.textBlocks.map { block ->
                OcrBlock(
                    text = block.text,
                    lines = block.lines.map { it.text }
                )
            }
            Result.Success(OcrResult(fullText = visionText.text, blocks = blocks))
        } catch (e: Exception) {
            CayanaLogger.w("MlKitOcrEngine", "Failed to process bitmap: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }
}
