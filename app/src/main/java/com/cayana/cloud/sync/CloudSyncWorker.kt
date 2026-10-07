package com.cayana.cloud.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.cayana.core.common.Result as CayanaResult
import com.cayana.core.logging.CayanaLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CloudSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val cloudSyncManager: CloudSyncManager by inject()

    override suspend fun doWork(): ListenableWorker.Result {
        CayanaLogger.i("CloudSyncWorker", "Starting background cloud sync work")
        return when (val result = cloudSyncManager.syncOnce()) {
            is CayanaResult.Success -> {
                CayanaLogger.i(
                    "CloudSyncWorker",
                    "Cloud sync finished successfully: pushed ${result.data.pushedCount}, pulled ${result.data.pulledCount}"
                )
                ListenableWorker.Result.success()
            }
            is CayanaResult.Error -> {
                CayanaLogger.w("CloudSyncWorker", "Cloud sync failed, will retry", result.exception)
                if (runAttemptCount < 3) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.failure()
                }
            }
            CayanaResult.Loading -> ListenableWorker.Result.retry()
        }
    }
}
