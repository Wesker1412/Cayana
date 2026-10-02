package com.cayana.backup

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

class StubBackupService : BackupService {
    override val backupState: BackupState = BackupState.IDLE

    override suspend fun createBackup(): Result<Unit> {
        return Result.Error(UnsupportedOperationException("Backup is not enabled in Stage 0"))
    }

    override suspend fun restoreBackup(): Result<Unit> {
        return Result.Error(UnsupportedOperationException("Restore is not enabled in Stage 0"))
    }
}
