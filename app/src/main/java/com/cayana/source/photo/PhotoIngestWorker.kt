package com.cayana.source.photo

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager worker scheduled with ContentUriTrigger on MediaStore.Images.Media.EXTERNAL_CONTENT_URI.
 * Ensures Cayana wakes up and processes photos even when the app process has been killed.
 */
class PhotoIngestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val coordinator: PhotoProcessingCoordinator by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val permissionChecker: PermissionChecker by inject()

    override suspend fun doWork(): Result {
        CayanaLogger.d("PhotoWorker", "PhotoIngestWorker woke up from MediaStore content trigger")
        if (!shouldReArm()) {
            CayanaLogger.i("PhotoWorker", "Watcher condition not met on wake; cancelling trigger and aborting.")
            cancelTrigger(applicationContext)
            return Result.success()
        }
        try {
            coordinator.processPendingPhotos()
        } catch (e: Exception) {
            CayanaLogger.w("PhotoWorker", "Error during background photo ingestion: ${e.javaClass.simpleName}")
        } finally {
            if (shouldReArm()) {
                scheduleNextTrigger(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            } else {
                CayanaLogger.i("PhotoWorker", "Watcher condition not met; cancelling and not re-arming trigger.")
                cancelTrigger(applicationContext)
            }
        }
        return Result.success()
    }

    private suspend fun shouldReArm(): Boolean {
        return try {
            val settings = settingsRepository.getSettings().first()
            val isEnabled = settings.enabledSources.contains(SourceType.PHOTO)
            val status = settings.photoWatcherStatus
            val permissionStatus = permissionChecker.getSourceStatus(
                sourceType = SourceType.PHOTO,
                isEnabled = isEnabled,
                isDenied = false,
                customUri = null
            )
            isEnabled && status == SourceWatcherStatus.ACTIVE &&
                    permissionStatus == SourceStatus.ENABLED_AND_AUTHORIZED &&
                    !permissionChecker.hasLimitedAccess(SourceType.PHOTO)
        } catch (e: Exception) {
            CayanaLogger.w("PhotoWorker", "Error checking shouldReArm: ${e.message}")
            false
        }
    }

    companion object {
        const val WORK_NAME = "cayana_photo_content_trigger"

        fun scheduleNextTrigger(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            try {
                val constraints = Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<PhotoIngestWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    policy,
                    workRequest
                )
            } catch (e: Exception) {
                CayanaLogger.w("PhotoWorker", "Failed to schedule next trigger: ${e.javaClass.simpleName}")
            }
        }

        fun cancelTrigger(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                CayanaLogger.w("PhotoWorker", "Failed to cancel trigger: ${e.javaClass.simpleName}")
            }
        }
    }
}
