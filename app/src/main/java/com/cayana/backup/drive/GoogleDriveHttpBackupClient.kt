package com.cayana.backup.drive

import com.cayana.backup.config.BackupConfig
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Production Google Drive client communicating with Google Drive v3 REST API.
 * Confined strictly to appDataFolder (`https://www.googleapis.com/auth/drive.appdata`).
 */
class GoogleDriveHttpBackupClient(
    private val tokenProvider: suspend () -> Result<String>
) : GoogleDriveBackupClient {

    companion object {
        private const val BASE_DRIVE_API = "https://www.googleapis.com/drive/v3/files"
        private const val BASE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
        private const val BOUNDARY = "cayana_drive_multipart_boundary_789456123"
    }

    override suspend fun listBackups(): Result<List<DriveBackupMetadata>> = withContext(Dispatchers.IO) {
        val token = when (val tokenResult = tokenProvider()) {
            is Result.Success -> tokenResult.data
            is Result.Error -> return@withContext Result.Error(tokenResult.exception)
            Result.Loading -> return@withContext Result.Loading
        }

        try {
            val queryUrl = "$BASE_DRIVE_API?spaces=${BackupConfig.DRIVE_SPACE_APP_DATA}" +
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
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                CayanaLogger.w("DriveClient", "Drive listBackups failed with HTTP $responseCode")
                return@withContext Result.Error(IOException("Google Drive API error: HTTP $responseCode - $errorBody"))
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val filesArray = json.optJSONArray("files") ?: return@withContext Result.Success(emptyList())

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

                // Only consider Cayana v1 backup files
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

            Result.Success(list.sortedByDescending { it.createdTimeMillis })
        } catch (e: Exception) {
            CayanaLogger.e("DriveClient", "Exception during Drive listBackups", e)
            Result.Error(e)
        }
    }

    override suspend fun uploadBackup(
        fileName: String,
        content: ByteArray,
        mimeType: String
    ): Result<DriveBackupMetadata> = withContext(Dispatchers.IO) {
        val token = when (val tokenResult = tokenProvider()) {
            is Result.Success -> tokenResult.data
            is Result.Error -> return@withContext Result.Error(tokenResult.exception)
            Result.Loading -> return@withContext Result.Loading
        }

        try {
            if (content.size <= BackupConfig.DRIVE_RESUMABLE_THRESHOLD_BYTES) {
                uploadMultipart(token, fileName, content, mimeType)
            } else {
                uploadResumable(token, fileName, content, mimeType)
            }
        } catch (e: Exception) {
            CayanaLogger.e("DriveClient", "Exception during Drive uploadBackup", e)
            Result.Error(e)
        }
    }

    private fun uploadMultipart(
        token: String,
        fileName: String,
        content: ByteArray,
        mimeType: String
    ): Result<DriveBackupMetadata> {
        val uploadUrl = "$BASE_UPLOAD_API?uploadType=multipart"
        val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")
            connectTimeout = 20000
            readTimeout = 30000
        }

        val metadataJson = JSONObject().apply {
            put("name", fileName)
            put("parents", org.json.JSONArray().put(BackupConfig.DRIVE_SPACE_APP_DATA))
        }.toString()

        val outputStream = connection.outputStream
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)

        // Part 1: Metadata
        writer.write("--$BOUNDARY\r\n")
        writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        writer.write(metadataJson)
        writer.write("\r\n")
        writer.flush()

        // Part 2: Media
        writer.write("--$BOUNDARY\r\n")
        writer.write("Content-Type: $mimeType\r\n\r\n")
        writer.flush()
        outputStream.write(content)
        outputStream.flush()

        // End boundary
        writer.write("\r\n--$BOUNDARY--\r\n")
        writer.flush()
        outputStream.close()

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
            return Result.Error(IOException("Drive multipart upload failed: HTTP $responseCode - $errorBody"))
        }

        val responseBody = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(responseBody)
        val fileId = json.getString("id")
        val size = json.optLong("size", content.size.toLong())

        return Result.Success(
            DriveBackupMetadata(
                fileId = fileId,
                fileName = fileName,
                sizeBytes = size,
                createdTimeMillis = System.currentTimeMillis()
            )
        )
    }

    private fun uploadResumable(
        token: String,
        fileName: String,
        content: ByteArray,
        mimeType: String
    ): Result<DriveBackupMetadata> {
        // Step 1: Initiate Resumable Upload Session
        val initUrl = "$BASE_UPLOAD_API?uploadType=resumable"
        val initConn = (URL(initUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mimeType)
            setRequestProperty("X-Upload-Content-Length", content.size.toString())
            connectTimeout = 20000
            readTimeout = 30000
        }

        val metadataJson = JSONObject().apply {
            put("name", fileName)
            put("parents", org.json.JSONArray().put(BackupConfig.DRIVE_SPACE_APP_DATA))
        }.toString()

        initConn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(metadataJson) }

        val initResponseCode = initConn.responseCode
        if (initResponseCode !in 200..299) {
            val errorBody = initConn.errorStream?.bufferedReader()?.readText() ?: ""
            return Result.Error(IOException("Drive resumable init failed: HTTP $initResponseCode - $errorBody"))
        }

        val sessionUri = initConn.getHeaderField("Location")
            ?: return Result.Error(IOException("Drive resumable init response missing Location header"))

        // Step 2: Upload Data to Resumable Session URI
        val uploadConn = (URL(sessionUri).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Length", content.size.toString())
            setRequestProperty("Content-Type", mimeType)
            connectTimeout = 30000
            readTimeout = 60000
        }

        uploadConn.outputStream.use { it.write(content) }

        val uploadResponseCode = uploadConn.responseCode
        if (uploadResponseCode !in 200..299) {
            val errorBody = uploadConn.errorStream?.bufferedReader()?.readText() ?: ""
            return Result.Error(IOException("Drive resumable data upload failed: HTTP $uploadResponseCode - $errorBody"))
        }

        val responseBody = uploadConn.inputStream.bufferedReader().readText()
        val json = JSONObject(responseBody)
        val fileId = json.getString("id")
        val size = json.optLong("size", content.size.toLong())

        return Result.Success(
            DriveBackupMetadata(
                fileId = fileId,
                fileName = fileName,
                sizeBytes = size,
                createdTimeMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun downloadBackup(fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val token = when (val tokenResult = tokenProvider()) {
            is Result.Success -> tokenResult.data
            is Result.Error -> return@withContext Result.Error(tokenResult.exception)
            Result.Loading -> return@withContext Result.Loading
        }

        try {
            val downloadUrl = "$BASE_DRIVE_API/$fileId?alt=media"
            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 20000
                readTimeout = 30000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                return@withContext Result.Error(IOException("Drive download failed: HTTP $responseCode - $errorBody"))
            }

            val baos = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                input.copyTo(baos)
            }
            Result.Success(baos.toByteArray())
        } catch (e: Exception) {
            CayanaLogger.e("DriveClient", "Exception during Drive downloadBackup", e)
            Result.Error(e)
        }
    }

    override suspend fun deleteBackup(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = when (val tokenResult = tokenProvider()) {
            is Result.Success -> tokenResult.data
            is Result.Error -> return@withContext Result.Error(tokenResult.exception)
            Result.Loading -> return@withContext Result.Loading
        }

        try {
            val deleteUrl = "$BASE_DRIVE_API/$fileId"
            val connection = (URL(deleteUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299 && responseCode != 404) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                return@withContext Result.Error(IOException("Drive delete failed: HTTP $responseCode - $errorBody"))
            }

            Result.Success(Unit)
        } catch (e: Exception) {
            CayanaLogger.e("DriveClient", "Exception during Drive deleteBackup", e)
            Result.Error(e)
        }
    }
}
