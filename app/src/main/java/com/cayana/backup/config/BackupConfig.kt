package com.cayana.backup.config

/**
 * Global configuration and bounds for Cayana encrypted backup and restore.
 */
object BackupConfig {
    const val BACKUP_FORMAT_VERSION: Short = 1
    const val FILE_NAME_PREFIX: String = "cayana-v1-"
    const val FILE_NAME_SUFFIX: String = ".cynb"

    // 4-byte magic identifying Cayana Backup Format v1: 'C', 'Y', 'N', 'B'
    val MAGIC: ByteArray = byteArrayOf(0x43, 0x59, 0x4E, 0x42)

    // Security & resource bounds
    const val MAX_ENCRYPTED_BACKUP_BYTES: Long = 50L * 1024 * 1024 // 50 MB
    const val MAX_DECRYPTED_BACKUP_BYTES: Long = 100L * 1024 * 1024 // 100 MB
    const val MAX_RECORD_COUNT: Int = 100_000
    const val MAX_RETENTION_COUNT: Int = 3

    // Drive upload strategy threshold: <= 5MB multipart, > 5MB resumable
    const val DRIVE_RESUMABLE_THRESHOLD_BYTES: Long = 5L * 1024 * 1024 // 5 MB

    // Google Drive appDataFolder configuration
    const val DRIVE_SCOPE: String = "https://www.googleapis.com/auth/drive.appdata"
    const val DRIVE_SPACE_APP_DATA: String = "appDataFolder"

    // Cryptographic parameters
    const val ROOT_KEY_BYTES_LENGTH: Int = 32 // 256 bits
    const val SALT_BYTES_LENGTH: Int = 16
    const val NONCE_BYTES_LENGTH: Int = 12
    const val GCM_TAG_LENGTH_BITS: Int = 128
    val HKDF_INFO: ByteArray = "CayanaBackupKeyV1".toByteArray(Charsets.UTF_8)

    // Allowed entries in decrypted backup archive
    val ENTRY_MANIFEST = "manifest.json"
    val ENTRY_MEMORIES = "memories.jsonl"
    val ENTRY_CALENDAR_ACTIONS = "calendar_action_history.jsonl"
    val ENTRY_SETTINGS = "settings.json"

    val ALLOWED_ARCHIVE_ENTRIES: Set<String> = setOf(
        ENTRY_MANIFEST,
        ENTRY_MEMORIES,
        ENTRY_CALENDAR_ACTIONS,
        ENTRY_SETTINGS
    )
}
