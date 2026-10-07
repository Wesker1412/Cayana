package com.cayana.backup.manager

import androidx.room.withTransaction
import com.cayana.backup.config.BackupConfig
import com.cayana.backup.crypto.BackupCryptoEngine
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.backup.crypto.RecoveryKeyStorage
import com.cayana.backup.drive.DriveAuthStatus
import com.cayana.backup.drive.DriveAuthorizationManager
import com.cayana.backup.drive.DriveBackupMetadata
import com.cayana.backup.drive.GoogleDriveBackupClient
import com.cayana.backup.snapshot.BackupSnapshotBuilder
import com.cayana.backup.snapshot.BackupSnapshotParser
import com.cayana.backup.snapshot.PortableUserSettings
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.data.RestoredCalendarActionHistoryDao
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.data.MemoryEntity
import com.cayana.memory.repository.MemoryRepository
import com.cayana.source.SourceExistenceValidator
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.cayana.backup.snapshot.PortableCalendarActionHistory
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

data class RestoreSummary(
    val memoriesRestored: Int,
    val calendarHistoryRestored: Int
)

class BackupManager(
    private val database: CayanaDatabase,
    private val memoryDao: MemoryDao,
    private val calendarActionDao: CalendarActionDao,
    private val restoredCalendarActionHistoryDao: RestoredCalendarActionHistoryDao,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val driveClient: GoogleDriveBackupClient,
    private val driveAuthManager: DriveAuthorizationManager,
    private val recoveryKeyStorage: RecoveryKeyStorage,
    private val sourceExistenceValidator: SourceExistenceValidator
) {

    private val backupOperationMutex = Mutex()

    /**
     * Executes client-side encrypted backup to Google Drive appDataFolder.
     */
    suspend fun performBackup(): Result<DriveBackupMetadata> = backupOperationMutex.withLock {
        // 1. Verify Drive Authorization
        when (val tokenResult = driveAuthManager.getAccessToken()) {
            is Result.Success -> Unit
            is Result.Error -> return Result.Error(tokenResult.exception)
            Result.Loading -> return Result.Error(IllegalStateException("Drive token loading"))
        }

        // 2. Load Recovery Root Key
        val rootKey = recoveryKeyStorage.loadRecoveryKey()
            ?: return Result.Error(IllegalStateException("尚未設定或找不到備份復原金鑰。"))

        try {
            // 3. Consistent DB snapshot in single transaction
            val (memories, actions) = database.withTransaction {
                val mems = memoryDao.getAllMemoriesDirect().map { it.toDomain() }
                val liveActions = calendarActionDao.getAll().map { PortableCalendarActionHistory.fromLiveAction(it) }
                val restoredHistory = restoredCalendarActionHistoryDao.getAll().map { PortableCalendarActionHistory.fromRestoredHistory(it) }
                val combined = (liveActions + restoredHistory).distinctBy { it.originalActionId }
                Pair(mems, combined)
            }

            // 4. Extract portable user settings
            val currentSettings = settingsRepository.getSettings().first()
            val portableSettings = PortableUserSettings.fromUserSettings(currentSettings)

            // 5. Build logical archive
            val snapshotId = UUID.randomUUID()
            val archiveBytes = BackupSnapshotBuilder.buildArchiveWithHistory(
                snapshotId = snapshotId.toString(),
                memories = memories,
                calendarActions = actions,
                portableSettings = portableSettings
            )
            if (archiveBytes.size > BackupConfig.MAX_DECRYPTED_BACKUP_BYTES) {
                return Result.Error(IllegalStateException("備份未加密資料超過大小限制（最大 ${BackupConfig.MAX_DECRYPTED_BACKUP_BYTES} 位元組）"))
            }

            // 6. Encrypt client-side using AES-256-GCM envelope
            val encryptedBlob = BackupCryptoEngine.encrypt(
                payload = archiveBytes,
                rootKey = rootKey,
                snapshotId = snapshotId
            )
            if (encryptedBlob.size > BackupConfig.MAX_ENCRYPTED_BACKUP_BYTES) {
                return Result.Error(IllegalStateException("備份加密檔案超過大小限制（最大 ${BackupConfig.MAX_ENCRYPTED_BACKUP_BYTES} 位元組）"))
            }

            // 7. Upload to Google Drive appDataFolder
            val fileName = "${BackupConfig.FILE_NAME_PREFIX}${snapshotId}${BackupConfig.FILE_NAME_SUFFIX}"
            val uploadResult = driveClient.uploadBackup(fileName, encryptedBlob)
            if (uploadResult !is Result.Success) {
                return uploadResult
            }

            val metadata = uploadResult.data
            val now = System.currentTimeMillis()
            settingsRepository.updateLastBackupTimestamp(now)

            // 8. Retention: Retain latest 3 successful snapshots and prune older ones
            pruneOldBackups()

            CayanaLogger.i("BackupManager", "Backup successfully created: ${metadata.fileName}, size: ${metadata.sizeBytes} bytes")
            return Result.Success(metadata)
        } catch (e: Exception) {
            CayanaLogger.e("BackupManager", "Backup execution failed", e)
            return Result.Error(e)
        }
    }

    /**
     * Lists existing backups in Google Drive appDataFolder.
     */
    suspend fun listBackups(): Result<List<DriveBackupMetadata>> {
        when (val tokenResult = driveAuthManager.getAccessToken()) {
            is Result.Success -> Unit
            is Result.Error -> return Result.Error(tokenResult.exception)
            Result.Loading -> return Result.Error(IllegalStateException("Drive token loading"))
        }
        return driveClient.listBackups()
    }

    /**
     * Restores canonical data from an encrypted backup blob.
     * All integrity, cryptographic, and schema validations are performed BEFORE touching the database.
     */
    suspend fun restoreBackup(
        fileId: String,
        rawRecoveryKey: String,
        onConfirmReplaceLocal: suspend () -> Boolean = { true }
    ): Result<RestoreSummary> = backupOperationMutex.withLock {
        // 1. Validate recovery key syntax & checksum
        val rootKey = try {
            RecoveryKeyManager.parseKey(rawRecoveryKey)
        } catch (e: Exception) {
            return Result.Error(e)
        }

        // 2. Download encrypted blob from Google Drive
        val downloadResult = driveClient.downloadBackup(fileId)
        if (downloadResult !is Result.Success) {
            return Result.Error(
                (downloadResult as? Result.Error)?.exception
                    ?: IOException("無法自 Google Drive 下載備份檔案。")
            )
        }
        val encryptedBlob = downloadResult.data
        if (encryptedBlob.size > BackupConfig.MAX_ENCRYPTED_BACKUP_BYTES) {
            return Result.Error(IllegalStateException("下載的備份檔案超過大小限制（最大 ${BackupConfig.MAX_ENCRYPTED_BACKUP_BYTES} 位元組）"))
        }

        // 3. Decrypt locally with fail-closed authentication
        val decrypted = try {
            BackupCryptoEngine.decrypt(encryptedBlob, rootKey)
        } catch (e: Exception) {
            return Result.Error(e)
        }

        // 4. Validate archive structure, whitelist entries, and record checksums
        val parsed = try {
            BackupSnapshotParser.parseAndValidate(decrypted.payloadBytes)
        } catch (e: Exception) {
            return Result.Error(e)
        }

        // 5. Replace confirmation if local data is non-empty
        val existingLocalCount = memoryDao.getAllMemoriesDirect().size
        if (existingLocalCount > 0) {
            val confirmed = onConfirmReplaceLocal()
            if (!confirmed) {
                return Result.Error(CancellationException("使用者取消了還原作業。"))
            }
        }

        // 6. Transactional Database Restore
        try {
            database.withTransaction {
                // Clear existing memories
                memoryDao.clearAll()
                // Clear restored calendar history (strictly never touches live calendar_actions)
                restoredCalendarActionHistoryDao.clearAll()

                // Insert canonical memories with source existence checked on this device
                for (mem in parsed.memories) {
                    val existence = sourceExistenceValidator.checkSourceExistence(mem.sourceUri, mem.sourceType)
                    val exists = when (existence) {
                        is com.cayana.source.SourceExistence.Exists -> true
                        is com.cayana.source.SourceExistence.Missing -> false
                        is com.cayana.source.SourceExistence.Unavailable -> mem.sourceExists
                    }
                    val toInsert = mem.copy(sourceExists = exists)
                    memoryRepository.saveMemory(toInsert, origin = com.cayana.memory.repository.MutationOrigin.RESTORE)
                }

                // Insert restored calendar actions into inert history table
                restoredCalendarActionHistoryDao.insertAll(parsed.calendarActions)
            }

            // 7. Apply portable settings (only portable preferences, never device-specific capabilities)
            settingsRepository.applyPortableSettings(parsed.settings)

            // 8. Save working recovery key locally wrapped with Keystore
            recoveryKeyStorage.saveRecoveryKey(rootKey)
            settingsRepository.updateHasRecoveryKey(true)

            // 9. Rebuild local Search Index
            runCatching {
                memoryRepository.rebuildSearchIndex()
            }.onFailure { e ->
                CayanaLogger.w("BackupManager", "Search index rebuild failed after restore, will be recovered by repair worker", e)
            }

            CayanaLogger.i("BackupManager", "Restore completed successfully: ${parsed.memories.size} memories, ${parsed.calendarActions.size} calendar history items")
            return Result.Success(
                RestoreSummary(
                    memoriesRestored = parsed.memories.size,
                    calendarHistoryRestored = parsed.calendarActions.size
                )
            )
        } catch (e: Exception) {
            CayanaLogger.e("BackupManager", "Transactional restore failed", e)
            return Result.Error(e)
        }
    }

    private suspend fun pruneOldBackups() {
        try {
            val listResult = driveClient.listBackups()
            if (listResult is Result.Success) {
                val backups = listResult.data
                if (backups.size > BackupConfig.MAX_RETENTION_COUNT) {
                    val toDelete = backups.drop(BackupConfig.MAX_RETENTION_COUNT)
                    for (oldBackup in toDelete) {
                        driveClient.deleteBackup(oldBackup.fileId)
                    }
                }
            }
        } catch (e: Exception) {
            CayanaLogger.w("BackupManager", "Failed to prune old backups", e)
        }
    }
}
