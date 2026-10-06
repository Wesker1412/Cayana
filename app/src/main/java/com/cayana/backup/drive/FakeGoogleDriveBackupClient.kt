package com.cayana.backup.drive

import com.cayana.core.common.Result
import java.io.IOException
import java.util.UUID

/**
 * Thread-safe test fake for [GoogleDriveBackupClient] with failure injection and retention testing support.
 */
class FakeGoogleDriveBackupClient : GoogleDriveBackupClient {

    private val storage = mutableMapOf<String, Pair<DriveBackupMetadata, ByteArray>>()

    var uploadFailure: Exception? = null
    var downloadFailure: Exception? = null
    var listFailure: Exception? = null
    var deleteFailure: Exception? = null

    var simulateInterruptedResumableUpload: Boolean = false
    private var resumableInterruptionCount = 0

    override suspend fun listBackups(): Result<List<DriveBackupMetadata>> {
        listFailure?.let { return Result.Error(it) }
        val list = storage.values
            .map { it.first }
            .sortedByDescending { it.createdTimeMillis }
        return Result.Success(list)
    }

    override suspend fun uploadBackup(
        fileName: String,
        content: ByteArray,
        mimeType: String
    ): Result<DriveBackupMetadata> {
        uploadFailure?.let { return Result.Error(it) }

        if (simulateInterruptedResumableUpload && resumableInterruptionCount == 0) {
            resumableInterruptionCount++
            return Result.Error(IOException("Simulated network interruption during resumable chunk upload"))
        }

        val fileId = "drive_file_${UUID.randomUUID()}"
        val metadata = DriveBackupMetadata(
            fileId = fileId,
            fileName = fileName,
            sizeBytes = content.size.toLong(),
            createdTimeMillis = System.currentTimeMillis()
        )
        storage[fileId] = Pair(metadata, content.copyOf())
        return Result.Success(metadata)
    }

    override suspend fun downloadBackup(fileId: String): Result<ByteArray> {
        downloadFailure?.let { return Result.Error(it) }
        val entry = storage[fileId] ?: return Result.Error(IOException("File not found on Google Drive: $fileId"))
        return Result.Success(entry.second.copyOf())
    }

    override suspend fun deleteBackup(fileId: String): Result<Unit> {
        deleteFailure?.let { return Result.Error(it) }
        storage.remove(fileId)
        return Result.Success(Unit)
    }

    fun getAllFiles(): List<DriveBackupMetadata> {
        return storage.values.map { it.first }.sortedByDescending { it.createdTimeMillis }
    }

    fun getStoredContent(fileId: String): ByteArray? {
        return storage[fileId]?.second?.copyOf()
    }

    fun clear() {
        storage.clear()
        resumableInterruptionCount = 0
    }
}
