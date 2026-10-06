package com.cayana.search

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.repository.MemoryRepository
import com.cayana.search.data.SearchIndexStateDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SearchIndexRepairWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val memoryRepository: MemoryRepository by inject()
    private val searchIndexStateDao: SearchIndexStateDao by inject()

    override suspend fun doWork(): Result {
        val isDirty = searchIndexStateDao.isDirty() == true || memoryRepository.isIndexRebuildNeeded()
        if (!isDirty) {
            return Result.success()
        }

        return try {
            CayanaLogger.i("SearchIndexRepair", "Starting background search index repair")
            memoryRepository.rebuildSearchIndex()
            val stillDirty = searchIndexStateDao.isDirty() == true || memoryRepository.isIndexRebuildNeeded()
            if (stillDirty) {
                CayanaLogger.w("SearchIndexRepair", "Search index still dirty after repair attempt")
                Result.retry()
            } else {
                CayanaLogger.i("SearchIndexRepair", "Search index repair completed successfully")
                Result.success()
            }
        } catch (e: Exception) {
            CayanaLogger.w("SearchIndexRepair", "Search index repair failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "cayana_search_index_repair"

        fun scheduleIfDirty(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<SearchIndexRepairWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                CayanaLogger.w("SearchIndexRepair", "Failed to enqueue search repair worker: ${e.javaClass.simpleName}")
            }
        }
    }
}
