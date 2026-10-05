package com.cayana.processing.stt

import android.content.Context
import com.cayana.ui.settings.repository.UserSettings
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
 * - Archive Size: 160,304,482 bytes (~152.8 MB compressed zip / 163 MB bz2)
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
    const val ARCHIVE_DOWNLOAD_SIZE_BYTES = 160304482L
    const val INSTALLED_SIZE_BYTES = 239549735L
    const val EXPECTED_MODEL_SHA256 = "C71F0CE00BEC95B07744E116345E33D8CBBE08CEF896382CF907BF4B51A2CD51"
    const val EXPECTED_TOKENS_SHA256 = "F449EB28DC567533D7FA59BE34E2ABCA8784F771850C78A47FB731A31429A1DC"

    const val READY_MARKER_FILE_NAME = ".ready"

    fun getModelDir(context: Context): File {
        return File(context.filesDir, "models/sense-voice")
    }

    fun getDownloadTempDir(context: Context): File {
        return File(context.filesDir, "models/.download")
    }

    fun getModelFile(context: Context): File {
        return File(getModelDir(context), "model.int8.onnx")
    }

    fun getTokensFile(context: Context): File {
        return File(getModelDir(context), "tokens.txt")
    }

    fun getReadyMarkerFile(context: Context): File {
        return File(getModelDir(context), READY_MARKER_FILE_NAME)
    }

    fun isModelInstalled(context: Context): Boolean {
        val modelFile = getModelFile(context)
        val tokensFile = getTokensFile(context)
        val readyMarker = getReadyMarkerFile(context)
        return modelFile.exists() && modelFile.length() > 0 &&
                tokensFile.exists() && tokensFile.length() > 0 &&
                readyMarker.exists() && readyMarker.length() > 0
    }

    /**
     * Checks if the model is ready with cached verified state validation.
     * Prevents re-computing SHA-256 for 239 MB files on every chunk while ensuring
     * modified, uncommitted, or missing files trigger full re-verification.
     */
    fun isModelReady(
        context: Context,
        settings: UserSettings,
        expectedModelSha256: String = EXPECTED_MODEL_SHA256,
        expectedTokensSha256: String = EXPECTED_TOKENS_SHA256
    ): Boolean {
        // First recover any interrupted swaps if needed
        recoverOrCleanStagingDirs(context)

        val modelFile = getModelFile(context)
        val tokensFile = getTokensFile(context)
        val readyMarker = getReadyMarkerFile(context)
        if (!modelFile.exists() || !tokensFile.exists() || !readyMarker.exists() ||
            modelFile.length() == 0L || tokensFile.length() == 0L || readyMarker.length() == 0L
        ) {
            return false
        }

        // Fast path: verify cached verified state
        val matchesCachedState = settings.sttModelId == MODEL_ID &&
                settings.sttModelVersion == MODEL_VERSION &&
                settings.verifiedModelSha256.equals(expectedModelSha256, ignoreCase = true) &&
                settings.verifiedTokensSha256.equals(expectedTokensSha256, ignoreCase = true) &&
                settings.sttModelFileSize == modelFile.length() &&
                settings.sttModelLastModified == modelFile.lastModified()

        if (matchesCachedState) {
            return true
        }

        // Slow path: full SHA-256 verification
        return verifyModelChecksums(context, expectedModelSha256, expectedTokensSha256)
    }

    fun verifyModelChecksums(
        context: Context,
        expectedModelSha256: String = EXPECTED_MODEL_SHA256,
        expectedTokensSha256: String = EXPECTED_TOKENS_SHA256
    ): Boolean {
        val modelFile = getModelFile(context)
        val tokensFile = getTokensFile(context)
        val readyMarker = getReadyMarkerFile(context)
        if (!modelFile.exists() || !tokensFile.exists() || !readyMarker.exists()) return false

        val modelHash = computeSha256(modelFile)
        val tokensHash = computeSha256(tokensFile)
        return modelHash.equals(expectedModelSha256, ignoreCase = true) &&
                tokensHash.equals(expectedTokensSha256, ignoreCase = true)
    }

    /**
     * Recovers from process crashes during atomic directory swaps and cleans leftover staging dirs.
     */
    fun recoverOrCleanStagingDirs(context: Context) {
        val modelsParent = File(context.filesDir, "models")
        if (!modelsParent.exists()) return

        val prodDir = getModelDir(context)
        val prodValid = prodDir.exists() &&
                File(prodDir, "model.int8.onnx").length() > 0 &&
                File(prodDir, "tokens.txt").length() > 0 &&
                File(prodDir, READY_MARKER_FILE_NAME).length() > 0

        val oldDirs = modelsParent.listFiles { f -> f.isDirectory && f.name.startsWith(".old-") } ?: emptyArray()

        if (!prodValid) {
            val candidate = oldDirs.firstOrNull { dir ->
                val m = File(dir, "model.int8.onnx")
                val t = File(dir, "tokens.txt")
                val r = File(dir, READY_MARKER_FILE_NAME)
                m.exists() && m.length() > 0 && t.exists() && t.length() > 0 && r.exists() && r.length() > 0
            }
            if (candidate != null) {
                if (prodDir.exists()) prodDir.deleteRecursively()
                candidate.renameTo(prodDir)
            }
        }

        // Clean up remaining staging, backup, and download temp directories
        modelsParent.listFiles { f ->
            f.isDirectory && (f.name.startsWith(".staging-") || f.name.startsWith(".old-") || f.name == ".download")
        }?.forEach { it.deleteRecursively() }
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
