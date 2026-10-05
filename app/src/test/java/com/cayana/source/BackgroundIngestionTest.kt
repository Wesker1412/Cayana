package com.cayana.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.cayana.core.common.Result
import com.cayana.core.permission.PermissionChecker
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrResult
import com.cayana.source.photo.PhotoIngestWorker
import com.cayana.source.photo.PhotoProcessingCoordinator
import com.cayana.source.recording.RecordingIngestWorker
import com.cayana.source.recording.RecordingProcessingCoordinator
import com.cayana.test.FakeMediaContentProvider
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.test.FakeSpeechToTextEngine
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundIngestionTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var photoCoordinator: PhotoProcessingCoordinator
    private lateinit var recordingCoordinator: RecordingProcessingCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaContentProvider.register(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)

        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                photoWatcherStatus = SourceWatcherStatus.ACTIVE,
                recordingWatcherStatus = SourceWatcherStatus.ACTIVE,
                enabledSources = setOf(SourceType.PHOTO, SourceType.RECORDING)
            )
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
            setPermissionGranted("android.permission.READ_MEDIA_AUDIO", true)
        }
        memoryRepository = FakeMemoryRepository()

        photoCoordinator = PhotoProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = FakeOcrEngine(Result.Success(OcrResult("Worker Test")))
        )

        recordingCoordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = FakeSpeechToTextEngine()
        )

        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        startKoin {
            modules(
                module {
                    single<PhotoProcessingCoordinator> { photoCoordinator }
                    single<RecordingProcessingCoordinator> { recordingCoordinator }
                    single<SettingsRepository> { settingsRepository }
                    single<PermissionChecker> { permissionChecker }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun photoIngestWorkerEnqueuesWithContentUriTrigger() {
        PhotoIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)

        val workInfos = workManager.getWorkInfosForUniqueWork(PhotoIngestWorker.WORK_NAME).get()
        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)
    }

    @Test
    fun photoIngestWorkerWhenSourceDisabledCancelsTriggerAndDoesNotReArm() = runTest {
        settingsRepository.updateSourceEnabled(SourceType.PHOTO, false)
        settingsRepository.updatePhotoWatcherStatus(SourceWatcherStatus.DISABLED)
        PhotoIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.REPLACE)

        val worker = TestListenableWorkerBuilder<PhotoIngestWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val workInfos = workManager.getWorkInfosForUniqueWork(PhotoIngestWorker.WORK_NAME).get()
        val notRunning = workInfos.isEmpty() || workInfos.all {
            it.state == WorkInfo.State.CANCELLED || it.state == WorkInfo.State.SUCCEEDED
        }
        assertTrue("Trigger should be cancelled and not re-armed when source is disabled", notRunning)
    }

    @Test
    fun recordingIngestWorkerEnqueuesWithContentUriTrigger() {
        RecordingIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)

        val workInfos = workManager.getWorkInfosForUniqueWork(RecordingIngestWorker.WORK_NAME).get()
        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)
    }

    @Test
    fun recordingIngestWorkerWhenSourceDisabledCancelsTriggerAndDoesNotReArm() = runTest {
        settingsRepository.updateSourceEnabled(SourceType.RECORDING, false)
        settingsRepository.updateRecordingWatcherStatus(SourceWatcherStatus.DISABLED)
        RecordingIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.REPLACE)

        val worker = TestListenableWorkerBuilder<RecordingIngestWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val workInfos = workManager.getWorkInfosForUniqueWork(RecordingIngestWorker.WORK_NAME).get()
        val notRunning = workInfos.isEmpty() || workInfos.all {
            it.state == WorkInfo.State.CANCELLED || it.state == WorkInfo.State.SUCCEEDED
        }
        assertTrue("Trigger should be cancelled and not re-armed when source is disabled", notRunning)
    }
}
