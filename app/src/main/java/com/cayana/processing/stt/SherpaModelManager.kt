package com.cayana.processing.stt

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Manages the on-device SenseVoice INT8 speech model files for sherpa-onnx.
 *
 * Exact Model Specifications:
 * - Engine: sherpa-onnx (OfflineRecognizer)
 * - Model ID: sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17
 * - Version: 2024-07-17
 * - Archive Size: 163,002,883 bytes (~155.4 MB compressed)
 * - Uncompressed Model File: model.int8.onnx (239,233,841 bytes)
 * - Tokens File: tokens.txt (315,894 bytes)
 * - Total Installed Size: 239,549,735 bytes (~228.4 MB)
 * - License: Apache 2.0
 * - Supported Languages: Mandarin (zh), English (en), Japanese (ja), Korean (ko), Cantonese (yue)
 * - Model SHA-256: C71F0CE00BEC95B07744E116345E33D8CBBE08CEF896382CF907BF4B51A2CD51
 * - Tokens SHA-256: F449EB28DC567533D7FA59BE34E2ABCA8784F771850C78A47FB731A31429A1DC
 */
object SherpaModelManager {

    const val MODEL_ID = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    const val MODEL_VERSION = "2024-07-17"
    const val LICENSE = "Apache 2.0"
    const val ARCHIVE_DOWNLOAD_SIZE_BYTES = 163002883L
    const val INSTALLED_SIZE_BYTES = 239549735L
    const val EXPECTED_MODEL_SHA256 = "C71F0CE00BEC95B07744E116345E33D8CBBE08CEF896382CF907BF4B51A2CD51"
    const val EXPECTED_TOKENS_SHA256 = "F449EB28DC567533D7FA59BE34E2ABCA8784F771850C78A47FB731A31429A1DC"

    fun getModelDir(context: Context): File {
        return File(context.filesDir, "models/sense-voice")
    }

    fun getModelFile(context: Context): File {
        return File(getModelDir(context), "model.int8.onnx")
    }

    fun getTokensFile(context: Context): File {
        return File(getModelDir(context), "tokens.txt")
    }

    fun isModelInstalled(context: Context): Boolean {
        val modelFile = getModelFile(context)
        val tokensFile = getTokensFile(context)
        return modelFile.exists() && modelFile.length() > 0 && tokensFile.exists() && tokensFile.length() > 0
    }

    fun verifyModelChecksums(context: Context): Boolean {
        val modelFile = getModelFile(context)
        val tokensFile = getTokensFile(context)
        if (!modelFile.exists() || !tokensFile.exists()) return false

        val modelHash = computeSha256(modelFile)
        val tokensHash = computeSha256(tokensFile)
        return modelHash.equals(EXPECTED_MODEL_SHA256, ignoreCase = true) &&
                tokensHash.equals(EXPECTED_TOKENS_SHA256, ignoreCase = true)
    }

    fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }
}
