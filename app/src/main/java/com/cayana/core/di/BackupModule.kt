package com.cayana.core.di

import com.cayana.backup.crypto.AndroidKeystoreRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyStorage
import com.cayana.backup.drive.DriveAuthorizationManager
import com.cayana.backup.drive.FileResumableUploadStateStore
import com.cayana.backup.drive.GoogleDriveAuthorizationManager
import com.cayana.backup.drive.GoogleDriveBackupClient
import com.cayana.backup.drive.GoogleDriveHttpBackupClient
import com.cayana.backup.drive.ResumableUploadStateStore
import com.cayana.backup.manager.BackupManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val backupModule = module {
    single<RecoveryKeyStorage> {
        AndroidKeystoreRecoveryKeyStorage(androidContext())
    }

    single<DriveAuthorizationManager> {
        GoogleDriveAuthorizationManager(androidContext())
    }

    single<ResumableUploadStateStore> {
        FileResumableUploadStateStore(File(androidContext().filesDir, "backup_upload"))
    }

    single<GoogleDriveBackupClient> {
        GoogleDriveHttpBackupClient(
            driveAuthManager = get(),
            resumableStateStore = get(),
            tempDir = File(androidContext().cacheDir, "backup_temp")
        )
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
