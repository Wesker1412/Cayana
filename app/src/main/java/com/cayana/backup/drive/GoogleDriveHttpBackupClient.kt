package com.cayana.backup.drive

import com.cayana.backup.config.BackupConfig
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

/**
 * Production Google Drive client communicating with Google Drive v3 REST API.
 * Confined strictly to appDataFolder (`https://www.googleapis.com/auth/drive.appdata`).
 * Features:
 * - Transparent 401 token refresh retry (exactly once, no infinite loop).
 * - Bounded streaming download (< 50MB).
 * - Resumable upload for payloads > 5MB with 1 MiB chunks and server Range negotiation.
 * - Durable session state tracking in app-private storage for surviving process death.
 * - Session URIs are strictly never logged.
 */
class GoogleDriveHttpBackupClient(
    private val driveAuthManager: DriveAuthorizationManager,
    private val resumableStateStore: ResumableUploadStateStore,
    private val tempDir: File,
    private val baseDriveApi: String = "https://www.googleapis.com/drive/v3/files",
    private val baseUploadApi: String = "https://www.googleapis.com/upload/drive/v3/files",
    private val chunkSize: Int = 1024 * 1024, // 1 MiB (multiple of 256 KiB)
    private val resumableThresholdBytes: Long = BackupConfig.DRIVE_RESUMABLE_THRESHOLD_BYTES
) : GoogleDriveBackupClient {

    companion object {
        private const val BOUNDARY = "cayana_drive_multipart_boundary_789456123"
    }

    private suspend fun <T> executeWithTokenRetry(
        action: suspend (token: String) -> Pair<Int, Result<T>>
    ): Result<T> {
        val initialToken = when (val res = driveAuthManager.getAccessToken()) {
            is Result.Success -> res.data
            is Result.Error -> return Result.Error(res.exception)
            Result.Loading -> return Result.Loading
        }

        val (firstCode, firstResult) = action(initialToken)
        if (firstCode == 401) {
            CayanaLogger.w("DriveClient", "Google Drive API returned HTTP 401. Refreshing token and retrying once.")
            driveAuthManager.invalidateCachedAccessToken()

            val refreshedToken = when (val res = driveAuthManager.getAccessToken()) {
                is Result.Success -> res.data
                is Result.Error -> return Result.Error(res.exception)
                Result.Loading -> return Result.Loading
            }

            val (secondCode, secondResult) = action(refreshedToken)
            if (secondCode == 401) {
                driveAuthManager.markAuthRequired()
                return Result.Error(IOException("Google Drive API authentication failed: HTTP 401 after retry."))
            }
            return secondResult
        }

        return firstResult
    }

    override suspend fun listBackups(): Result<List<DriveBackupMetadata>> = withContext(Dispatchers.IO) {
        executeWithTokenRetry { token ->
            try {
                val queryUrl = "$baseDriveApi?spaces=${BackupConfig.DRIVE_SPACE_APP_DATA}" +
                        "&q='${BackupConfig.DRIVE_SPACE_APP_DATA}'+in+parents+and+trashed=false" +
                        "&fields=files(id,name,size,createdTime)" +
                        "&orderBy=createdTime+desc"

                val connection = (URL(queryUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val responseCode = connection.responseCode
                if (responseCode == 401) {
                    return@executeWithTokenRetry 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
                }

                if (responseCode !in 200..299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                    return@executeWithTokenRetry responseCode to Result.Error(
                        IOException("Google Drive API error: HTTP $responseCode - $errorBody")
                    )
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)
                val filesArray = json.optJSONArray("files") ?: return@executeWithTokenRetry responseCode to Result.Success(emptyList())

                val list = mutableListOf<DriveBackupMetadata>()
                for (i in 0 until filesArray.length()) {
                    val fileObj = filesArray.getJSONObject(i)
                    val id = fileObj.getString("id")
                    val name = fileObj.getString("name")
                    val size = fileObj.optLong("size", 0L)
                    val createdTimeStr = fileObj.optString("createdTime", "")
                    val createdTimeMillis = runCatching {
                        Instant.parse(createdTimeStr).toEpochMilli()
                    }.getOrDefault(System.currentTimeMillis())

                    if (name.startsWith(BackupConfig.FILE_NAME_PREFIX) && name.endsWith(BackupConfig.FILE_NAME_SUFFIX)) {
                        list.add(
                            DriveBackupMetadata(
                                fileId = id,
                                fileName = name,
                                sizeBytes = size,
                                createdTimeMillis = createdTimeMillis
                            )
                        )
                    }
                }

                responseCode to Result.Success(list.sortedByDescending { it.createdTimeMillis })
            } catch (e: Exception) {
                -1 to Result.Error(e)
            }
        }
    }

    override suspend fun uploadBackup(
        fileName: String,
        content: ByteArray,
        mimeType: String
    ): Result<DriveBackupMetadata> = withContext(Dispatchers.IO) {
        if (content.size.toLong() <= resumableThresholdBytes) {
            uploadMultipart(fileName, content, mimeType)
        } else {
            uploadResumable(fileName, content)
        }
    }

    private suspend fun uploadMultipart(
        fileName: String,
        content: ByteArray,
        mimeType: String
    ): Result<DriveBackupMetadata> = executeWithTokenRetry { token ->
        try {
            val uploadUrl = "$baseUploadApi?uploadType=multipart"
            val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")
                doOutput = true
                connectTimeout = 20000
                readTimeout = 30000
            }

            val parentsArray = JSONArray()
            parentsArray.put(BackupConfig.DRIVE_SPACE_APP_DATA)
            val metadataJson = JSONObject().apply {
                put("name", fileName)
                put("parents", parentsArray)
            }.toString()

            val outputStream = connection.outputStream
            val newLine = "\r\n".toByteArray(Charsets.UTF_8)

            outputStream.write("--$BOUNDARY\r\n".toByteArray(Charsets.UTF_8))
            outputStream.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
            outputStream.write(metadataJson.toByteArray(Charsets.UTF_8))
            outputStream.write(newLine)

            outputStream.write("--$BOUNDARY\r\n".toByteArray(Charsets.UTF_8))
            outputStream.write("Content-Type: $mimeType\r\n\r\n".toByteArray(Charsets.UTF_8))
            outputStream.write(content)
            outputStream.write(newLine)

            outputStream.write("--$BOUNDARY--\r\n".toByteArray(Charsets.UTF_8))
            outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode == 401) {
                return@executeWithTokenRetry 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
            }

            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                return@executeWithTokenRetry responseCode to Result.Error(
                    IOException("Multipart upload failed: HTTP $responseCode - $errorBody")
                )
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val fileId = json.getString("id")
            val size = json.optLong("size", content.size.toLong())

            responseCode to Result.Success(
                DriveBackupMetadata(
                    fileId = fileId,
                    fileName = fileName,
                    sizeBytes = size,
                    createdTimeMillis = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            -1 to Result.Error(e)
        }
    }

    private suspend fun uploadResumable(
        fileName: String,
        content: ByteArray
    ): Result<DriveBackupMetadata> {
        val snapshotId = extractSnapshotId(fileName, content)
        tempDir.mkdirs()
        val tempCiphertextFile = File(tempDir, "backup_encrypted_${snapshotId}.tmp")
        if (!tempCiphertextFile.exists() || tempCiphertextFile.length() != content.size.toLong()) {
            tempCiphertextFile.writeBytes(content)
        }
        val totalBytes = tempCiphertextFile.length()

        return executeWithTokenRetry { token ->
            try {
                var state = resumableStateStore.loadState()
                if (state != null && (state.snapshotId != snapshotId || !File(state.encryptedTempFilePath).exists())) {
                    resumableStateStore.clearState()
                    state = null
                }

                val isResumed = (state != null)
                var currentState: ResumableUploadState = state ?: run {
                    val sessionResult = initiateResumableSession(token, fileName, totalBytes)
                    if (sessionResult.first == 401) {
                        return@executeWithTokenRetry 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
                    }
                    if (sessionResult.second !is Result.Success) {
                        return@executeWithTokenRetry sessionResult.first to Result.Error(
                            (sessionResult.second as Result.Error).exception
                        )
                    }
                    val sessionUri = (sessionResult.second as Result.Success).data
                    val newState = ResumableUploadState(
                        snapshotId = snapshotId,
                        encryptedTempFilePath = tempCiphertextFile.absolutePath,
                        sessionUri = sessionUri,
                        totalBytes = totalBytes,
                        confirmedBytes = 0L,
                        createdAt = System.currentTimeMillis()
                    )
                    resumableStateStore.saveState(newState)
                    newState
                }

                // If persisted session exists, ALWAYS query server status regardless of confirmedBytes
                var currentOffset = currentState.confirmedBytes
                if (isResumed) {
                    val queryResult = querySessionStatus(currentState.sessionUri, totalBytes)
                    when {
                        queryResult.first == 200 || queryResult.first == 201 -> {
                            val metadata = parseMetadataFromJson(queryResult.third, fileName, totalBytes)
                            tempCiphertextFile.delete()
                            resumableStateStore.clearState()
                            return@executeWithTokenRetry queryResult.first to Result.Success(metadata)
                        }
                        queryResult.first == 308 -> {
                            currentOffset = queryResult.second ?: currentState.confirmedBytes
                            currentState = currentState.copy(confirmedBytes = currentOffset)
                            resumableStateStore.saveState(currentState)
                        }
                        queryResult.first == 404 -> {
                            // Session expired on server; initiate brand new session
                            val newSession = initiateResumableSession(token, fileName, totalBytes)
                            if (newSession.second !is Result.Success) {
                                return@executeWithTokenRetry newSession.first to Result.Error(
                                    (newSession.second as Result.Error).exception
                                )
                            }
                            currentState = currentState.copy(
                                sessionUri = (newSession.second as Result.Success).data,
                                confirmedBytes = 0L
                            )
                            resumableStateStore.saveState(currentState)
                            currentOffset = 0L
                        }
                        queryResult.first == 401 -> {
                            return@executeWithTokenRetry 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
                        }
                    }
                }

                // Upload chunks sequentially
                val raf = RandomAccessFile(tempCiphertextFile, "r")
                try {
                    while (currentOffset < totalBytes) {
                        val start = currentOffset
                        val end = minOf(start + chunkSize, totalBytes)
                        val sliceLength = (end - start).toInt()
                        val buffer = ByteArray(sliceLength)
                        raf.seek(start)
                        raf.readFully(buffer)

                        val chunkConn = (URL(currentState.sessionUri).openConnection() as HttpURLConnection).apply {
                            requestMethod = "PUT"
                            instanceFollowRedirects = false
                            setRequestProperty("Content-Range", "bytes $start-${end - 1}/$totalBytes")
                            setRequestProperty("Content-Length", sliceLength.toString())
                            setRequestProperty("Content-Type", "application/octet-stream")
                            doOutput = true
                            connectTimeout = 30000
                            readTimeout = 30000
                        }

                        chunkConn.outputStream.use { out ->
                            out.write(buffer)
                            out.flush()
                        }

                        val code = chunkConn.responseCode
                        if (code == 401) {
                            return@executeWithTokenRetry 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
                        }

                        if (code == 308) {
                            val rangeHeader = chunkConn.getHeaderField("Range")
                            val parsedRange = parseRangeHeader(rangeHeader)
                            if (parsedRange != null) {
                                currentOffset = parsedRange
                            } else {
                                // 308 without Range: query session status or safely fall back to current chunk start
                                val qResult = querySessionStatus(currentState.sessionUri, totalBytes)
                                if (qResult.first == 308 && qResult.second != null) {
                                    currentOffset = qResult.second!!
                                } else if (qResult.first in 200..299) {
                                    val metadata = parseMetadataFromJson(qResult.third, fileName, totalBytes)
                                    tempCiphertextFile.delete()
                                    resumableStateStore.clearState()
                                    return@executeWithTokenRetry qResult.first to Result.Success(metadata)
                                } else {
                                    currentOffset = start
                                }
                            }
                            currentState = currentState.copy(confirmedBytes = currentOffset)
                            resumableStateStore.saveState(currentState)
                        } else if (code in 200..299) {
                            val responseBody = chunkConn.inputStream.bufferedReader().readText()
                            val metadata = parseMetadataFromJson(responseBody, fileName, totalBytes)
                            tempCiphertextFile.delete()
                            resumableStateStore.clearState()
                            return@executeWithTokenRetry code to Result.Success(metadata)
                        } else if (code == 404) {
                            // Session expired
                            val newSession = initiateResumableSession(token, fileName, totalBytes)
                            if (newSession.second !is Result.Success) {
                                return@executeWithTokenRetry newSession.first to Result.Error(
                                    (newSession.second as Result.Error).exception
                                )
                            }
                            currentState = currentState.copy(
                                sessionUri = (newSession.second as Result.Success).data,
                                confirmedBytes = 0L
                            )
                            resumableStateStore.saveState(currentState)
                            currentOffset = 0L
                        } else {
                            val err = chunkConn.errorStream?.bufferedReader()?.readText() ?: ""
                            return@executeWithTokenRetry code to Result.Error(
                                IOException("Chunk upload failed: HTTP $code - $err")
                            )
                        }
                    }
                } finally {
                    raf.close()
                }

                200 to Result.Error(IOException("Resumable upload loop ended unexpectedly"))
            } catch (e: Exception) {
                -1 to Result.Error(e)
            }
        }
    }

    private fun initiateResumableSession(
        token: String,
        fileName: String,
        totalBytes: Long
    ): Pair<Int, Result<String>> {
        val url = "$baseUploadApi?uploadType=resumable"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            setRequestProperty("X-Upload-Content-Length", totalBytes.toString())
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val parentsArray = JSONArray()
        parentsArray.put(BackupConfig.DRIVE_SPACE_APP_DATA)
        val metadataJson = JSONObject().apply {
            put("name", fileName)
            put("parents", parentsArray)
        }.toString()

        connection.outputStream.use { out ->
            out.write(metadataJson.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        val code = connection.responseCode
        if (code == 401) {
            return 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
        }

        val location = connection.getHeaderField("Location")
        if (code in 200..299 && !location.isNullOrBlank()) {
            return code to Result.Success(location)
        }

        val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
        return code to Result.Error(IOException("Failed to initiate resumable session: HTTP $code - $err"))
    }

    private fun querySessionStatus(sessionUri: String, totalBytes: Long): Triple<Int, Long?, String> {
        val connection = (URL(sessionUri).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            instanceFollowRedirects = false
            setRequestProperty("Content-Range", "bytes */$totalBytes")
            setRequestProperty("Content-Length", "0")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val code = connection.responseCode
        val responseBody = if (code in 200..299) {
            connection.inputStream.bufferedReader().readText()
        } else ""

        val rangeHeader = connection.getHeaderField("Range")
        val nextOffset = parseRangeHeader(rangeHeader)
        return Triple(code, nextOffset, responseBody)
    }

    private fun parseRangeHeader(rangeHeader: String?): Long? {
        if (rangeHeader.isNullOrBlank()) return null
        // Format: bytes=0-1048575
        val match = Regex("""bytes=\d+-(\d+)""").find(rangeHeader)
        return match?.groupValues?.get(1)?.toLongOrNull()?.plus(1)
    }

    private fun parseMetadataFromJson(jsonStr: String, fallbackName: String, fallbackSize: Long): DriveBackupMetadata {
        val json = if (jsonStr.isNotBlank()) JSONObject(jsonStr) else JSONObject()
        return DriveBackupMetadata(
            fileId = json.optString("id", UUID.randomUUID().toString()),
            fileName = json.optString("name", fallbackName),
            sizeBytes = json.optLong("size", fallbackSize),
            createdTimeMillis = System.currentTimeMillis()
        )
    }

    private fun extractSnapshotId(fileName: String, bytes: ByteArray): String {
        val fromCiphertext = extractSnapshotIdFromCiphertext(bytes)
        if (fromCiphertext != null) return fromCiphertext

        val nameMatch = Regex("""cayana-v1-([0-9a-fA-F-]+)\.cynb""").find(fileName)
        if (nameMatch != null) {
            return nameMatch.groupValues[1]
        }
        return UUID.nameUUIDFromBytes(fileName.toByteArray()).toString()
    }

    private fun extractSnapshotIdFromCiphertext(bytes: ByteArray): String? {
        if (bytes.size < 22) return null
        if (bytes[0] != BackupConfig.MAGIC[0] ||
            bytes[1] != BackupConfig.MAGIC[1] ||
            bytes[2] != BackupConfig.MAGIC[2] ||
            bytes[3] != BackupConfig.MAGIC[3]
        ) return null
        val buffer = ByteBuffer.wrap(bytes, 6, 16)
        val msb = buffer.long
        val lsb = buffer.long
        return UUID(msb, lsb).toString()
    }

    override suspend fun downloadBackup(fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        executeWithTokenRetry { token ->
            try {
                val downloadUrl = "$baseDriveApi/$fileId?alt=media"
                val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 20000
                    readTimeout = 30000
                }

                val responseCode = connection.responseCode
                if (responseCode == 401) {
                    return@executeWithTokenRetry 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
                }

                if (responseCode !in 200..299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                    return@executeWithTokenRetry responseCode to Result.Error(
                        IOException("Drive download failed: HTTP $responseCode - $errorBody")
                    )
                }

                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var bytesReadTotal = 0L
                connection.inputStream.use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        bytesReadTotal += read
                        if (bytesReadTotal > BackupConfig.MAX_ENCRYPTED_BACKUP_BYTES) {
                            return@executeWithTokenRetry -1 to Result.Error(
                                IOException("下載之備份大小超過安全限制 (${BackupConfig.MAX_ENCRYPTED_BACKUP_BYTES} bytes)，已中止下載。")
                            )
                        }
                        baos.write(buffer, 0, read)
                    }
                }

                responseCode to Result.Success(baos.toByteArray())
            } catch (e: Exception) {
                -1 to Result.Error(e)
            }
        }
    }

    override suspend fun deleteBackup(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        executeWithTokenRetry { token ->
            try {
                val deleteUrl = "$baseDriveApi/$fileId"
                val connection = (URL(deleteUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val responseCode = connection.responseCode
                if (responseCode == 401) {
                    return@executeWithTokenRetry 401 to Result.Error(IOException("HTTP 401 Unauthorized"))
                }

                if (responseCode !in 200..299 && responseCode != 404) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                    return@executeWithTokenRetry responseCode to Result.Error(
                        IOException("Drive delete failed: HTTP $responseCode - $errorBody")
                    )
                }

                responseCode to Result.Success(Unit)
            } catch (e: Exception) {
                -1 to Result.Error(e)
            }
        }
    }
}
