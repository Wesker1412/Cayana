package com.cayana.core.di

import com.cayana.backup.crypto.AndroidKeystoreRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyStorage
import com.cayana.backup.drive.DriveAuthorizationManager
import com.cayana.backup.drive.GoogleDriveAuthorizationManager
import com.cayana.backup.drive.GoogleDriveBackupClient
import com.cayana.backup.drive.GoogleDriveHttpBackupClient
import com.cayana.backup.manager.BackupManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single<RecoveryKeyStorage> {
        AndroidKeystoreRecoveryKeyStorage(androidContext())
    }

    single<DriveAuthorizationManager> {
        GoogleDriveAuthorizationManager(androidContext())
    }

    single<GoogleDriveBackupClient> {
        val authManager = get<DriveAuthorizationManager>()
        GoogleDriveHttpBackupClient(tokenProvider = { authManager.getAccessToken() })
    }

    single {
        BackupManager(
            database = get(),
            memoryDao = get(),
            calendarActionDao = get(),
            restoredCalendarActionHistoryDao = get(),
            memoryRepository = get(),
            settingsRepository = get(),
            driveClient = get(),
            driveAuthManager = get(),
            recoveryKeyStorage = get(),
            sourceExistenceValidator = get()
        )
    }
}
