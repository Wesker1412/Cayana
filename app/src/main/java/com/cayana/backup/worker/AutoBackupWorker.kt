package com.cayana.backup.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cayana.backup.drive.DriveAuthRequiredException
import com.cayana.backup.drive.DriveAuthStatus
import com.cayana.backup.drive.DriveAuthorizationManager
import com.cayana.backup.manager.BackupManager
import com.cayana.core.common.Result as CayanaResult
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker executing periodic automatic encrypted backups to Google Drive appDataFolder.
 * Runs strictly in the background without prompting user or launching Activities.
 */
class AutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val backupManager: BackupManager by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val driveAuthManager: DriveAuthorizationManager by inject()

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val settings = settingsRepository.getSettings().first()
        if (!settings.autoBackupEnabled) {
            CayanaLogger.i("AutoBackupWorker", "auto-backup is disabled in settings, skipping")
            return androidx.work.ListenableWorker.Result.success()
        }

        // Verify token in background. If resolution is required, NEVER launch UI.
        when (val tokenResult = driveAuthManager.getAccessToken()) {
            is CayanaResult.Success -> Unit
            is CayanaResult.Error -> {
                if (tokenResult.exception is DriveAuthRequiredException) {
                    CayanaLogger.w("AutoBackupWorker", "Google Drive authorization expired, marking AUTH_REQUIRED")
                    driveAuthManager.markAuthRequired()
                    settingsRepository.updateDriveAuthStatus(DriveAuthStatus.AUTH_REQUIRED)
                    return androidx.work.ListenableWorker.Result.failure()
                } else {
                    CayanaLogger.w("AutoBackupWorker", "Transient network error obtaining token")
                    return androidx.work.ListenableWorker.Result.retry()
                }
            }
            CayanaResult.Loading -> return androidx.work.ListenableWorker.Result.retry()
        }

        return when (val backupResult = backupManager.performBackup()) {
            is CayanaResult.Success -> {
                CayanaLogger.i("AutoBackupWorker", "AutoBackupWorker completed successfully: ${backupResult.data.fileName}")
                androidx.work.ListenableWorker.Result.success()
            }
            is CayanaResult.Error -> {
                if (backupResult.exception is DriveAuthRequiredException) {
                    driveAuthManager.markAuthRequired()
                    settingsRepository.updateDriveAuthStatus(DriveAuthStatus.AUTH_REQUIRED)
                    androidx.work.ListenableWorker.Result.failure()
                } else {
                    CayanaLogger.w("AutoBackupWorker", "AutoBackupWorker failed, retrying later: ${backupResult.exception.message}")
                    androidx.work.ListenableWorker.Result.retry()
                }
            }
            CayanaResult.Loading -> androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "cayana_auto_drive_backup"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
