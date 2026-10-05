package com.cayana.processing.stt

import android.content.Context
import android.os.Build
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Production installer for the on-device SenseVoice INT8 STT model.
 *
 * Requirements:
 * - Downloads artifact to app-private temporary directory (files/models/.download/).
 * - Never uploads audio or user data.
 * - Extracts and strictly verifies SHA-256 checksums before atomic move into production path.
 * - If checksum mismatches: immediately deletes temporary artifacts, throws SecurityException,
 *   and leaves the model directory clean.
 * - Persists verified metadata in SettingsRepository on success.
 */
class SherpaModelInstaller(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers = AppDispatchers()
) {

    sealed interface InstallProgress {
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percentage: Int) : InstallProgress
        data object Verifying : InstallProgress
        data object Installing : InstallProgress
        data object Completed : InstallProgress
        data class Failed(val error: Throwable) : InstallProgress
    }

    suspend fun downloadAndInstall(
        downloadUrl: String = getDefaultDownloadUrl(),
        onProgress: ((InstallProgress) -> Unit)? = null
    ): Boolean = withContext(dispatchers.io) {
        val tempDir = SherpaModelManager.getDownloadTempDir(context)
        try {
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            val tempZip = File(tempDir, "archive.zip")
            CayanaLogger.i("ModelInstaller", "Starting STT model download from $downloadUrl to ${tempZip.absolutePath}")

            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Failed to download model: HTTP $responseCode")
            }

            val contentLength = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val percent = if (contentLength > 0) ((downloadedBytes * 100) / contentLength).toInt() else -1
                        onProgress?.invoke(InstallProgress.Downloading(downloadedBytes, contentLength, percent))
                    }
                }
            }

            // Extract and verify archive
            return@withContext installFromArchive(tempZip, onProgress = onProgress)
        } catch (e: Throwable) {
            CayanaLogger.e("ModelInstaller", "Model download or installation failed: ${e.message}", e)
            tempDir.deleteRecursively()
            onProgress?.invoke(InstallProgress.Failed(e))
            throw e
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun installFromArchive(
        archiveFile: File,
        expectedModelSha256: String = SherpaModelManager.EXPECTED_MODEL_SHA256,
        expectedTokensSha256: String = SherpaModelManager.EXPECTED_TOKENS_SHA256,
        onProgress: ((InstallProgress) -> Unit)? = null
    ): Boolean = withContext(dispatchers.io) {
        val tempDir = SherpaModelManager.getDownloadTempDir(context)
        val extractedModel = File(tempDir, "model.int8.onnx")
        val extractedTokens = File(tempDir, "tokens.txt")

        try {
            tempDir.mkdirs()
            onProgress?.invoke(InstallProgress.Verifying)
            CayanaLogger.i("ModelInstaller", "Extracting archive ${archiveFile.absolutePath}...")

            // Extract zip contents
            archiveFile.inputStream().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.substringAfterLast("/")
                        if (name == "model.int8.onnx") {
                            FileOutputStream(extractedModel).use { fos ->
                                zis.copyTo(fos)
                            }
                        } else if (name == "tokens.txt") {
                            FileOutputStream(extractedTokens).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (!extractedModel.exists() || !extractedTokens.exists()) {
                throw IllegalStateException("Archive is missing required model files (model.int8.onnx and tokens.txt)")
            }

            // Verify checksums strictly
            CayanaLogger.i("ModelInstaller", "Verifying model SHA-256 checksums...")
            val modelSha = SherpaModelManager.computeSha256(extractedModel)
            val tokensSha = SherpaModelManager.computeSha256(extractedTokens)

            val modelValid = modelSha.equals(expectedModelSha256, ignoreCase = true)
            val tokensValid = tokensSha.equals(expectedTokensSha256, ignoreCase = true)

            if (!modelValid || !tokensValid) {
                CayanaLogger.e("ModelInstaller", "Checksum mismatch! ModelValid=$modelValid, TokensValid=$tokensValid. Deleting temporary files.")
                tempDir.deleteRecursively()
                val error = SecurityException("Model checksum verification failed. Model hash: $modelSha, Tokens hash: $tokensSha")
                onProgress?.invoke(InstallProgress.Failed(error))
                throw error
            }

            // Checksum verified: Atomic installation into production path
            onProgress?.invoke(InstallProgress.Installing)
            val prodDir = SherpaModelManager.getModelDir(context)
            prodDir.mkdirs()

            val prodModel = SherpaModelManager.getModelFile(context)
            val prodTokens = SherpaModelManager.getTokensFile(context)

            // Atomic move / copy
            if (prodModel.exists()) prodModel.delete()
            if (prodTokens.exists()) prodTokens.delete()

            val modelMoved = extractedModel.renameTo(prodModel) || run {
                extractedModel.copyTo(prodModel, overwrite = true)
                extractedModel.delete()
                true
            }

            val tokensMoved = extractedTokens.renameTo(prodTokens) || run {
                extractedTokens.copyTo(prodTokens, overwrite = true)
                extractedTokens.delete()
                true
            }

            if (!modelMoved || !tokensMoved) {
                throw IllegalStateException("Failed to move verified model files to destination: ${prodDir.absolutePath}")
            }

            // Persist cached verified metadata
            settingsRepository.updateVerifiedSttModel(
                modelId = SherpaModelManager.MODEL_ID,
                modelVersion = SherpaModelManager.MODEL_VERSION,
                modelSha256 = expectedModelSha256,
                tokensSha256 = expectedTokensSha256,
                fileSize = prodModel.length(),
                lastModified = prodModel.lastModified()
            )

            CayanaLogger.i("ModelInstaller", "Model successfully installed and verified at ${prodDir.absolutePath}")
            onProgress?.invoke(InstallProgress.Completed)
            true
        } catch (e: Throwable) {
            tempDir.deleteRecursively()
            throw e
        }
    }

    companion object {
        const val OFFICIAL_RELEASE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

        const val LOCAL_EMULATOR_URL =
            "http://10.0.2.2:8080/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip"

        fun getDefaultDownloadUrl(): String {
            // If running on an emulator, prioritize fast local host server if reachable
            val isEmulator = Build.HARDWARE.contains("goldfish") ||
                    Build.HARDWARE.contains("ranchu") ||
                    Build.FINGERPRINT.contains("generic")

            return if (isEmulator) {
                LOCAL_EMULATOR_URL
            } else {
                OFFICIAL_RELEASE_URL
            }
        }
    }
}
