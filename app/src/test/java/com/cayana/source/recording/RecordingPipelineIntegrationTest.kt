package com.cayana.source.recording

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import com.cayana.test.FakePermissionChecker
import com.cayana.test.FakeSpeechToTextEngine
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingPipelineIntegrationTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var fakeSttEngine: FakeSpeechToTextEngine
    private lateinit var coordinator: RecordingProcessingCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        com.cayana.test.FakeMediaContentProvider.register(context)
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                enabledSources = setOf(SourceType.RECORDING),
                recordingWatcherStatus = SourceWatcherStatus.ACTIVE
            )
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_AUDIO", true)
        }
        fakeSttEngine = FakeSpeechToTextEngine()
        coordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = fakeSttEngine,
            chunkDurationMs = 30_000L
        ).apply {
            autoTranscribeSync = true
        }
    }

    private fun insertFakeRecording(
        name: String = "Voice_001.m4a",
        path: String = "Recordings/",
        mime: String = "audio/mp4",
        durationMs: Long = 60_000L,
        isRecording: Boolean? = true
    ): Long {
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.RELATIVE_PATH, path)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.DURATION, durationMs)
            put(MediaStore.Audio.Media.SIZE, 10240L)
            put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PODCAST, 0)
            put(MediaStore.Audio.Media.IS_RINGTONE, 0)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
            put(MediaStore.Audio.Media.IS_ALARM, 0)
            if (isRecording != null) {
                put(MediaStore.Audio.AudioColumns.IS_RECORDING, if (isRecording) 1 else 0)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
        assertNotNull(uri)
        return android.content.ContentUris.parseId(uri!!)
    }

    @Test
    fun newRecordingPersistedImmediatelyAndSttCompletes() = runTest {
        insertFakeRecording("Voice_001.m4a", durationMs = 60_000L)

        val processed = coordinator.processPendingRecordings()
        assertEquals(1, processed.size)

        val memory = memoryRepository.getAllMemories().first()[0]
        assertEquals(SourceType.RECORDING, memory.sourceType)
        assertTrue(memory.sourceExists)
        assertEquals(ProcessingState.COMPLETED, memory.processingState)
        assertEquals("Chunk 0 text Chunk 1 text", memory.rawText)
        assertEquals(2, fakeSttEngine.chunksTranscribed.size)
    }

    @Test
    fun modelUnavailablePersistsMemoryAsWaitingForModel() = runTest {
        fakeSttEngine.isModelAvailable = false
        insertFakeRecording("Voice_waiting.m4a", durationMs = 30_000L)

        val processed = coordinator.processPendingRecordings()
        assertEquals(1, processed.size)

        val memory = memoryRepository.getAllMemories().first()[0]
        assertEquals(SourceType.RECORDING, memory.sourceType)
        assertEquals("Recording memory must persist even when model is unavailable",
            ProcessingState.WAITING_FOR_MODEL, memory.processingState)
        assertEquals(0, fakeSttEngine.chunksTranscribed.size)

        // Now model becomes available -> reconcile should complete it!
        fakeSttEngine.isModelAvailable = true
        coordinator.reconcileInFlightRecordings()

        val updated = memoryRepository.getMemoryById(memory.id).first()
        assertNotNull(updated)
        assertEquals(ProcessingState.COMPLETED, updated!!.processingState)
        assertEquals("Chunk 0 text", updated.rawText)
    }

    @Test
    fun sttTransientFailurePersistsMemoryAsFailedRetryable() = runTest {
        fakeSttEngine.simulateFailureAtChunk = 0
        insertFakeRecording("Voice_failure.m4a", durationMs = 30_000L)

        coordinator.processPendingRecordings()

        val memory = memoryRepository.getAllMemories().first()[0]
        assertEquals("STT failure must not delete recording memory",
            ProcessingState.FAILED_RETRYABLE, memory.processingState)
        assertEquals(1, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun duplicateTriggerProducesOnlyOneMemory() = runTest {
        insertFakeRecording("Voice_dedup.m4a", durationMs = 30_000L)

        val firstRun = coordinator.processPendingRecordings()
        assertEquals(1, firstRun.size)

        val secondRun = coordinator.processPendingRecordings()
        assertEquals(0, secondRun.size)
        assertEquals(1, memoryRepository.getMemoryCount().first())
    }

    @Test
    fun sourceDeletedAfterTranscriptKeepsTranscriptAndMarksSourceExistsFalse() = runTest {
        insertFakeRecording("Voice_delete.m4a", durationMs = 30_000L)
        val processed = coordinator.processPendingRecordings()
        assertEquals(1, processed.size)
        val memory = processed[0]

        // Delete audio from MediaStore
        val uri = android.net.Uri.parse(memory.sourceUri!!)
        context.contentResolver.delete(uri, null, null)

        val reconciled = coordinator.reconcileDeletedRecordings()
        assertEquals(1, reconciled)

        val updated = memoryRepository.getMemoryById(memory.id).first()
        assertNotNull(updated)
        assertFalse(updated!!.sourceExists)
        assertEquals("Transcript must be retained after source audio is deleted", "Chunk 0 text", updated.rawText)
    }

    @Test
    fun longAudioRecoveryResumesFromCheckpointWithoutRestartingChunk0() = runTest {
        // Section 32: 5 chunks (150 seconds, 30s/chunk)
        fakeSttEngine.audioDurationMs = 150_000L
        fakeSttEngine.simulateFailureAtChunk = 2 // Fails at chunk 2 (third chunk: indices 0, 1, 2)

        insertFakeRecording("Voice_long.m4a", durationMs = 150_000L)

        // First pass: chunk 0 succeeds, chunk 1 succeeds, chunk 2 fails
        coordinator.processPendingRecordings()

        val initialMemory = memoryRepository.getAllMemories().first()[0]
        assertEquals(ProcessingState.FAILED_RETRYABLE, initialMemory.processingState)
        assertEquals("2", initialMemory.metadata["completedChunks"])
        assertEquals("Chunk 0 text", initialMemory.metadata["chunk_0"])
        assertEquals("Chunk 1 text", initialMemory.metadata["chunk_1"])
        assertEquals(listOf(0, 1, 2), fakeSttEngine.chunksTranscribed)

        // Simulate Process Death: clear in-memory engine state and rebuild coordinator
        fakeSttEngine.chunksTranscribed.clear()
        fakeSttEngine.simulateFailureAtChunk = null // Recovered!

        val restartedCoordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = fakeSttEngine,
            chunkDurationMs = 30_000L
        ).apply {
            autoTranscribeSync = true
        }

        // Process restart catch-up / reconciliation
        val reconciledCount = restartedCoordinator.reconcileInFlightRecordings()
        assertEquals(1, reconciledCount)

        // Verify that it resumed from chunk 2 and did NOT re-run chunk 0 or 1!
        assertEquals("Should resume strictly from chunk 2 to chunk 4",
            listOf(2, 3, 4), fakeSttEngine.chunksTranscribed)

        val finalMemory = memoryRepository.getMemoryById(initialMemory.id).first()
        assertNotNull(finalMemory)
        assertEquals(ProcessingState.COMPLETED, finalMemory!!.processingState)
        assertEquals(
            "Final assembled transcript must contain all chunks without duplication",
            "Chunk 0 text Chunk 1 text Chunk 2 text Chunk 3 text Chunk 4 text",
            finalMemory.rawText
        )
    }
}
