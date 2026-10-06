package com.cayana.backup.drive

import com.cayana.core.common.Result

/**
 * Abstraction for interacting with Google Drive appDataFolder storage.
 */
interface GoogleDriveBackupClient {

    /**
     * Lists existing Cayana backups stored in appDataFolder, sorted by created time descending.
     */
    suspend fun listBackups(): Result<List<DriveBackupMetadata>>

    /**
     * Uploads an encrypted backup blob into Google Drive appDataFolder.
     */
    suspend fun uploadBackup(
        fileName: String,
        content: ByteArray,
        mimeType: String = "application/octet-stream"
    ): Result<DriveBackupMetadata>

    /**
     * Downloads an encrypted backup blob from Google Drive appDataFolder by fileId.
     */
    suspend fun downloadBackup(fileId: String): Result<ByteArray>

    /**
     * Permanently deletes a backup file from Google Drive appDataFolder.
     */
    suspend fun deleteBackup(fileId: String): Result<Unit>
}
