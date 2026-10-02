package com.cayana.source.screenshot

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cayana.core.logging.CayanaLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager worker scheduled with ContentUriTrigger on MediaStore.Images.Media.EXTERNAL_CONTENT_URI.
 * Ensures Cayana wakes up and processes screenshots even when the app process has been killed.
 */
class ScreenshotIngestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val coordinator: ScreenshotProcessingCoordinator by inject()

    override suspend fun doWork(): Result {
        CayanaLogger.d("ScreenshotWorker", "ScreenshotIngestWorker woke up from MediaStore content trigger")
        try {
            coordinator.processPendingScreenshots()
        } catch (e: Exception) {
            CayanaLogger.w("ScreenshotWorker", "Error during background screenshot ingestion: ${e.javaClass.simpleName}")
        } finally {
            // Re-register content trigger for subsequent screenshots
            scheduleNextTrigger(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "cayana_screenshot_content_trigger"

        fun scheduleNextTrigger(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<ScreenshotIngestWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            } catch (e: Exception) {
                CayanaLogger.w("ScreenshotWorker", "Failed to schedule next trigger: ${e.javaClass.simpleName}")
            }
        }

        fun cancelTrigger(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                CayanaLogger.w("ScreenshotWorker", "Failed to cancel trigger: ${e.javaClass.simpleName}")
            }
        }
    }
}
