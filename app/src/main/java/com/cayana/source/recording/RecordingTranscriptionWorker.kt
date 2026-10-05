package com.cayana.source.recording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.repository.MemoryRepository
import com.cayana.processing.ProcessingState
import kotlinx.coroutines.flow.firstOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Dedicated per-recording WorkManager worker for executing heavy transcription.
 *
 * Requirements:
 * - Uses unique work per memoryId ("cayana_recording_stt_<memoryId>") with ExistingWorkPolicy.KEEP
 *   to ensure exactly-once execution.
 * - Constraints enforced directly via RecordingConfig.getConstraints(durationMs).
 * - If conditions are unmet (e.g. charging or battery not low required), WorkManager defers execution
 *   while the Memory safely remains in PROCESSING/queued state.
 */
class RecordingTranscriptionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val coordinator: RecordingProcessingCoordinator by inject()
    private val memoryRepository: MemoryRepository by inject()

    override suspend fun doWork(): Result {
        val memoryId = inputData.getString(KEY_MEMORY_ID) ?: return Result.failure()
        CayanaLogger.i("RecordingSttWorker", "Running transcription work for memory $memoryId")

        val memory = memoryRepository.getMemoryById(memoryId).firstOrNull() ?: return Result.failure()
        if (memory.processingState == ProcessingState.COMPLETED ||
            memory.processingState == ProcessingState.COMPLETED_WITHOUT_TEXT
        ) {
            return Result.success()
        }

        val result = coordinator.transcribeRecording(memory)
        return when (result.processingState) {
            ProcessingState.COMPLETED, ProcessingState.COMPLETED_WITHOUT_TEXT -> Result.success()
            ProcessingState.WAITING_FOR_MODEL -> Result.success()
            ProcessingState.FAILED_RETRYABLE -> Result.retry()
            ProcessingState.FAILED_PERMANENT, ProcessingState.FAILED -> Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_MEMORY_ID = "key_memory_id"

        fun getWorkName(memoryId: String): String = "cayana_recording_stt_$memoryId"

        fun scheduleTranscription(
            context: Context,
            memoryId: String,
            durationMs: Long
        ) {
            try {
                val constraints = RecordingConfig.getConstraints(durationMs)
                val workRequest = OneTimeWorkRequestBuilder<RecordingTranscriptionWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(KEY_MEMORY_ID to memoryId))
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    getWorkName(memoryId),
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
                CayanaLogger.i("RecordingSttWorker", "Scheduled transcription work for $memoryId with duration $durationMs ms")
            } catch (e: Exception) {
                CayanaLogger.w("RecordingSttWorker", "Failed to schedule transcription work: ${e.message}")
            }
        }
    }
}
