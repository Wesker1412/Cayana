package com.cayana.source.screenshot

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
import com.cayana.source.SourceType
import com.cayana.test.FakeMediaContentProvider
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.ScreenshotWatcherStatus
import com.cayana.ui.settings.repository.SettingsRepository
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotWorkManagerTriggerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var coordinator: ScreenshotProcessingCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaContentProvider.register(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)

        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                screenshotWatcherStatus = ScreenshotWatcherStatus.ACTIVE,
                enabledSources = setOf(SourceType.SCREENSHOT)
            )
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
        }
        memoryRepository = FakeMemoryRepository()
        coordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = FakeOcrEngine(Result.Success(OcrResult("Worker Test")))
        )

        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        startKoin {
            androidContext(context)
            modules(
                module {
                    single<ScreenshotProcessingCoordinator> { coordinator }
                    single<SettingsRepository> { settingsRepository }
                    single<PermissionChecker> { permissionChecker }
                }
            )
        }
        try {
            workManager.cancelAllWork().result.get()
        } catch (_: Exception) {}
    }

    @After
    fun tearDown() {
        try {
            ScreenshotIngestWorker.cancelTrigger(context)
            workManager.cancelAllWork().result.get()
        } catch (_: Exception) {}
        stopKoin()
    }

    @Test
    fun `scheduleNextTrigger enqueues unique work with KEEP policy by default`() {
        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)

        val workInfos = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)

        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)
        val workInfosAfterKeep = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        assertEquals(1, workInfosAfterKeep.size)
        assertEquals(workInfos[0].id, workInfosAfterKeep[0].id)
    }

    @Test
    fun `cancelTrigger cancels the unique work request`() {
        ScreenshotIngestWorker.scheduleNextTrigger(context)
        ScreenshotIngestWorker.cancelTrigger(context)

        val workInfos = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        assertNotNull(workInfos)
        assertEquals(WorkInfo.State.CANCELLED, workInfos[0].state)
    }

    @Test
    fun `worker execution re-arms next trigger with APPEND_OR_REPLACE and maintains exactly 1 active work`() = runTest {
        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)
        val initialWorks = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        assertEquals(1, initialWorks.size)
        val firstId = initialWorks[0].id

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(firstId)
        awaitWorkFinished(firstId)

        val activeWorks = awaitActiveWorks(1)
        assertEquals("There must be exactly 1 active work scheduled", 1, activeWorks.size)
        assertEquals(WorkInfo.State.ENQUEUED, activeWorks[0].state)
    }

    @Test
    fun `consecutive worker executions do not accumulate duplicate work requests`() = runTest {
        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)
        val initialWorks = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        val firstId = initialWorks[0].id

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(firstId)
        awaitWorkFinished(firstId)

        val secondWorks = awaitActiveWorks(1)
        assertEquals(1, secondWorks.size)
        val secondId = secondWorks[0].id

        testDriver.setAllConstraintsMet(secondId)
        awaitWorkFinished(secondId)

        val thirdWorks = awaitActiveWorks(1)
        assertEquals("Consecutive executions must maintain only 1 active work request without stacking", 1, thirdWorks.size)
    }

    @Test
    fun `worker execution does not re-arm when source is disabled`() = runTest {
        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)
        val initialWorks = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        val firstId = initialWorks[0].id

        settingsRepository.updateSourceEnabled(SourceType.SCREENSHOT, false)

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(firstId)
        awaitWorkFinished(firstId)

        assertTrue("Active work must be cancelled when source is disabled", awaitNoActiveWorks())
    }

    @Test
    fun `worker execution does not re-arm when permission is revoked`() = runTest {
        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)
        val initialWorks = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        val firstId = initialWorks[0].id

        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_IMAGES", false)

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(firstId)
        awaitWorkFinished(firstId)

        assertTrue("Active work must be cancelled when permission is revoked", awaitNoActiveWorks())
    }

    @Test
    fun `worker execution does not re-arm when watcher status is not ACTIVE`() = runTest {
        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)
        val initialWorks = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        val firstId = initialWorks[0].id

        settingsRepository.updateScreenshotWatcherStatus(ScreenshotWatcherStatus.DISABLED)

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(firstId)
        awaitWorkFinished(firstId)

        assertTrue("Active work must be cancelled when watcher status is not ACTIVE", awaitNoActiveWorks())
    }

    private fun awaitWorkFinished(workId: java.util.UUID, timeoutMs: Long = 5000): WorkInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val info = workManager.getWorkInfoById(workId).get()
            if (info != null && info.state.isFinished) {
                return info
            }
            Thread.sleep(50)
        }
        return workManager.getWorkInfoById(workId).get() ?: error("Work not found")
    }

    private fun awaitActiveWorks(expectedCount: Int = 1, timeoutMs: Long = 5000): List<WorkInfo> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val active = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
                .filter { !it.state.isFinished }
            if (active.size == expectedCount) return active
            Thread.sleep(50)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        return workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
            .filter { !it.state.isFinished }
    }

    private fun awaitNoActiveWorks(timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val active = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
                .filter { !it.state.isFinished }
            if (active.isEmpty()) return true
            Thread.sleep(50)
        }
        return workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
            .none { !it.state.isFinished }
    }
}
