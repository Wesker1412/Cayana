package com.cayana.backup.drive

/**
 * Metadata for a Cayana backup file stored in Google Drive appDataFolder.
 */
data class DriveBackupMetadata(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdTimeMillis: Long,
    val snapshotId: String? = null
)
