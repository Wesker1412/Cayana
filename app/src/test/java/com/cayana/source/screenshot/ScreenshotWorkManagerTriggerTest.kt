package com.cayana.source.screenshot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotWorkManagerTriggerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `scheduleNextTrigger enqueues unique work with KEEP policy by default`() {
        // External call (e.g. from startWatching) uses KEEP policy
        ScreenshotIngestWorker.scheduleNextTrigger(context, ExistingWorkPolicy.KEEP)

        val workInfos = workManager.getWorkInfosForUniqueWork(ScreenshotIngestWorker.WORK_NAME).get()
        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)

        // Scheduling again with KEEP should keep the existing work request
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
}
