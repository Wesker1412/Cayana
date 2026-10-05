package com.cayana.processing.stt

import android.content.Context
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Production installer for the on-device SenseVoice INT8 STT model.
 *
 * Requirements:
 * - Downloads artifact from official immutable public HTTPS URL.
 * - Extracts both official tar.bz2 and zip archives into an isolated staging directory (.staging-<UUID>).
 * - Strictly verifies SHA-256 checksums of every required file before commit.
 * - Writes .ready marker and executes atomic directory swap (renames old -> .old-<UUID>, staging -> sense-voice).
 * - If process fails at any point: previous verified model remains intact and usable, or no model is left.
 *   Never leaves a partial/half-installed production model.
 * - Persists verified metadata in SettingsRepository on success.
 */
class SherpaModelInstaller(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers = AppDispatchers(),
    private val customDownloadUrl: String? = null
) {

    sealed interface InstallProgress {
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percentage: Int) : InstallProgress
        data object Verifying : InstallProgress
        data object Installing : InstallProgress
        data object Completed : InstallProgress
        data class Failed(val error: Throwable) : InstallProgress
    }

    suspend fun downloadAndInstall(
        downloadUrl: String = customDownloadUrl ?: getDefaultDownloadUrl(),
        onProgress: ((InstallProgress) -> Unit)? = null
    ): Boolean = withContext(dispatchers.io) {
        val tempDir = SherpaModelManager.getDownloadTempDir(context)
        try {
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            val isTarBz2 = downloadUrl.contains(".tar.bz2") || downloadUrl.contains(".bz2")
            val archiveFileName = if (isTarBz2) "archive.tar.bz2" else "archive.zip"
            val tempArchive = File(tempDir, archiveFileName)
            CayanaLogger.i("ModelInstaller", "Starting STT model download from $downloadUrl to ${tempArchive.absolutePath}")

            var currentUrl = downloadUrl
            var connection: HttpURLConnection? = null
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.connect()

                val responseCode = conn.responseCode
                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = location
                        redirects++
                        conn.disconnect()
                        continue
                    }
                }

                if (responseCode !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("Failed to download model: HTTP $responseCode")
                }

                connection = conn
                break
            }

            val activeConn = connection ?: throw IllegalStateException("Failed to establish connection to $downloadUrl")
            val contentLength = activeConn.contentLength.toLong()
            var downloadedBytes = 0L

            activeConn.inputStream.use { input ->
                FileOutputStream(tempArchive).use { output ->
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

            // Extract, verify, and atomically install
            return@withContext installFromArchive(tempArchive, onProgress = onProgress)
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
        val modelsParent = File(context.filesDir, "models")
        modelsParent.mkdirs()

        // Isolated staging directory
        val stagingDir = File(modelsParent, ".staging-${UUID.randomUUID()}")
        val extractedModel = File(stagingDir, "model.int8.onnx")
        val extractedTokens = File(stagingDir, "tokens.txt")

        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            onProgress?.invoke(InstallProgress.Verifying)
            CayanaLogger.i("ModelInstaller", "Extracting archive ${archiveFile.absolutePath} into staging: ${stagingDir.absolutePath}...")

            val isTarBz2 = archiveFile.name.endsWith(".tar.bz2") || archiveFile.name.endsWith(".bz2")
            if (isTarBz2) {
                archiveFile.inputStream().buffered().use { fis ->
                    BZip2CompressorInputStream(fis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                val name = entry.name.substringAfterLast("/")
                                if (name == "model.int8.onnx") {
                                    FileOutputStream(extractedModel).use { fos ->
                                        tarIn.copyTo(fos)
                                    }
                                } else if (name == "tokens.txt") {
                                    FileOutputStream(extractedTokens).use { fos ->
                                        tarIn.copyTo(fos)
                                    }
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }
            } else {
                archiveFile.inputStream().buffered().use { fis ->
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
            }

            if (!extractedModel.exists() || !extractedTokens.exists()) {
                throw IllegalStateException("Archive is missing required model files (model.int8.onnx and tokens.txt)")
            }

            // Verify checksums strictly in staging directory before commit
            CayanaLogger.i("ModelInstaller", "Verifying staging model SHA-256 checksums...")
            val modelSha = SherpaModelManager.computeSha256(extractedModel)
            val tokensSha = SherpaModelManager.computeSha256(extractedTokens)

            val modelValid = modelSha.equals(expectedModelSha256, ignoreCase = true)
            val tokensValid = tokensSha.equals(expectedTokensSha256, ignoreCase = true)

            if (!modelValid || !tokensValid) {
                CayanaLogger.e("ModelInstaller", "Checksum mismatch! ModelValid=$modelValid, TokensValid=$tokensValid. Deleting staging directory.")
                stagingDir.deleteRecursively()
                val error = SecurityException("Model checksum verification failed. Model hash: $modelSha, Tokens hash: $tokensSha")
                onProgress?.invoke(InstallProgress.Failed(error))
                throw error
            }

            // Write ready marker into staging
            val readyMarker = File(stagingDir, SherpaModelManager.READY_MARKER_FILE_NAME)
            readyMarker.writeText(
                "MODEL_ID=${SherpaModelManager.MODEL_ID}\n" +
                "VERSION=${SherpaModelManager.MODEL_VERSION}\n" +
                "MODEL_SHA=$expectedModelSha256\n" +
                "TOKENS_SHA=$expectedTokensSha256\n" +
                "INSTALLED_AT=${System.currentTimeMillis()}\n"
            )

            // Atomic directory swap
            onProgress?.invoke(InstallProgress.Installing)
            val prodDir = SherpaModelManager.getModelDir(context)
            val backupDir = File(modelsParent, ".old-${UUID.randomUUID()}")

            if (prodDir.exists()) {
                val backupSuccess = prodDir.renameTo(backupDir)
                if (!backupSuccess) {
                    stagingDir.deleteRecursively()
                    throw IllegalStateException("Failed to move existing model directory to backup")
                }
            }

            val installSuccess = stagingDir.renameTo(prodDir)
            if (installSuccess) {
                backupDir.deleteRecursively()
            } else {
                // Rollback
                if (backupDir.exists()) {
                    backupDir.renameTo(prodDir)
                }
                stagingDir.deleteRecursively()
                throw IllegalStateException("Failed to move staging directory to production path: ${prodDir.absolutePath}")
            }

            val prodModel = SherpaModelManager.getModelFile(context)

            // Persist cached verified metadata
            settingsRepository.updateVerifiedSttModel(
                modelId = SherpaModelManager.MODEL_ID,
                modelVersion = SherpaModelManager.MODEL_VERSION,
                modelSha256 = expectedModelSha256,
                tokensSha256 = expectedTokensSha256,
                fileSize = prodModel.length(),
                lastModified = prodModel.lastModified()
            )

            CayanaLogger.i("ModelInstaller", "Model successfully atomically installed and verified at ${prodDir.absolutePath}")
            onProgress?.invoke(InstallProgress.Completed)
            true
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw e
        }
    }

    companion object {
        const val OFFICIAL_RELEASE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

        fun getDefaultDownloadUrl(): String {
            return OFFICIAL_RELEASE_URL
        }
    }
}
