package com.cayana.backup

import com.cayana.backup.manager.BackupManager
import com.cayana.core.common.Result

enum class BackupState {
    IDLE,
    IN_PROGRESS,
    SUCCESS,
    FAILED
}

interface BackupService {
    val backupState: BackupState
    suspend fun createBackup(): Result<Unit>
    suspend fun restoreBackup(): Result<Unit>
}

class DefaultBackupService(
    private val backupManager: BackupManager
) : BackupService {
    override var backupState: BackupState = BackupState.IDLE
        private set

    override suspend fun createBackup(): Result<Unit> {
        backupState = BackupState.IN_PROGRESS
        return when (val res = backupManager.performBackup()) {
            is Result.Success -> {
                backupState = BackupState.SUCCESS
                Result.Success(Unit)
            }
            is Result.Error -> {
                backupState = BackupState.FAILED
                Result.Error(res.exception)
            }
            Result.Loading -> Result.Loading
        }
    }

    override suspend fun restoreBackup(): Result<Unit> {
        return Result.Error(UnsupportedOperationException("Use BackupManager.restoreBackup with fileId and recoveryKey"))
    }
}
