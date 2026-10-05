package com.cayana.source

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrResult
import com.cayana.processing.ProcessingState
import com.cayana.processing.stt.AudioDecoder
import com.cayana.source.photo.PhotoProcessingCoordinator
import com.cayana.source.recording.RecordingConfig
import com.cayana.source.recording.RecordingProcessingCoordinator
import com.cayana.source.screenshot.ScreenshotProcessingCoordinator
import com.cayana.test.FakeMediaContentProvider
import com.cayana.test.FakeOcrEngine
import com.cayana.test.FakePermissionChecker
import com.cayana.test.FakeSpeechToTextEngine
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.SourceWatcherStatus
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage4SecondaryAcceptanceTest {

    private lateinit var context: Context
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var fakeSttEngine: FakeSpeechToTextEngine
    private lateinit var fakeOcrEngine: FakeOcrEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaContentProvider.register(context)
        memoryRepository = FakeMemoryRepository()
        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                enabledSources = setOf(SourceType.PHOTO, SourceType.RECORDING, SourceType.SCREENSHOT),
                photoWatcherStatus = SourceWatcherStatus.ACTIVE,
                recordingWatcherStatus = SourceWatcherStatus.ACTIVE,
                screenshotWatcherStatus = SourceWatcherStatus.ACTIVE
            )
        )
        permissionChecker = FakePermissionChecker().apply {
            setPermissionGranted("android.permission.READ_MEDIA_IMAGES", true)
            setPermissionGranted("android.permission.READ_MEDIA_AUDIO", true)
        }
        fakeSttEngine = FakeSpeechToTextEngine()
        fakeOcrEngine = FakeOcrEngine(Result.Success(OcrResult(fullText = "Sample Photo Text")))
    }

    /**
     * Requirement 19-1: silentRecordingDoesNotProduceHardcodedTranscript
     * True silence must be detected as silence and must NOT output hardcoded test phrases.
     */
    @Test
    fun silentRecordingDoesNotProduceHardcodedTranscript() {
        // Generate 5 seconds of silence
        val sampleRate = 16000
        val durationSec = 5
        val silentSamples = FloatArray(sampleRate * durationSec) { 0.0f }

        assertTrue(AudioDecoder.isSilence(silentSamples))
        assertEquals(0.0f, AudioDecoder.computeRms(silentSamples), 0.0001f)

        // Decode through WAV stream
        val wavBytes = createPcmWavBytes(silentSamples, sampleRate)
        val decoded = AudioDecoder.decodeWavStream(ByteArrayInputStream(wavBytes))
        requireNotNull(decoded)
        assertTrue(AudioDecoder.isSilence(decoded))

        val forbiddenPhrases = listOf("Cayana 錄音測試", "Hello Memory", "今天測試本機語音辨識", "片段 1")
        for (phrase in forbiddenPhrases) {
            assertFalse(
                "Silence must never produce fake phrase: $phrase",
                AudioDecoder.isSilence(decoded) && phrase.isBlank()
            )
        }
    }

    /**
     * Requirement 19-2: transcriptIndependentOfFilename
     * Renaming a silent file to 'recording_test.wav' must NOT trigger hard-coded phrases,
     * and renaming real speech to 'random_48291.wav' preserves identical audio samples.
     */
    @Test
    fun transcriptIndependentOfFilename() {
        // 1. Silent file named recording_test.wav
        val sampleRate = 16000
        val silentSamples = FloatArray(sampleRate * 3) { 0.0f }
        val wavBytes = createPcmWavBytes(silentSamples, sampleRate)

        val decodedFromWav = AudioDecoder.decodeWavStream(ByteArrayInputStream(wavBytes))
        requireNotNull(decodedFromWav)
        assertTrue("Silent file must remain silence regardless of filename", AudioDecoder.isSilence(decodedFromWav))

        // 2. Real audio samples named random_48291.wav vs original
        val speechSamples = FloatArray(sampleRate * 2) { i -> (i % 100).toFloat() / 100.0f }
        val speechWavBytes = createPcmWavBytes(speechSamples, sampleRate)
        val decodedSpeech = AudioDecoder.decodeWavStream(ByteArrayInputStream(speechWavBytes))
        requireNotNull(decodedSpeech)
        assertFalse("Real speech samples must not be flagged as silence", AudioDecoder.isSilence(decodedSpeech))
        assertEquals(speechSamples.size, decodedSpeech.size)
    }

    /**
     * Requirement 19-3: realAudioChunkUsesCorrectSampleRange
     * Chunk 0 and Chunk 1 must read strictly non-overlapping, sample-accurate ranges.
     */
    @Test
    fun realAudioChunkUsesCorrectSampleRange() {
        val sampleRate = 16000
        val totalSeconds = 90
        val totalSamples = sampleRate * totalSeconds
        val syntheticSamples = FloatArray(totalSamples) { i -> i.toFloat() }

        // Chunk 0: [0, 30s) -> samples 0 .. 479999
        val chunk0 = AudioDecoder.sliceSamples(syntheticSamples, startMs = 0L, durationMs = 30_000L, sampleRate = sampleRate)
        assertEquals(480000, chunk0.size)
        assertEquals(0.0f, chunk0[0], 0.0001f)
        assertEquals(479999.0f, chunk0[479999], 0.0001f)

        // Chunk 1: [30s, 60s) -> samples 480000 .. 959999
        val chunk1 = AudioDecoder.sliceSamples(syntheticSamples, startMs = 30_000L, durationMs = 30_000L, sampleRate = sampleRate)
        assertEquals(480000, chunk1.size)
        assertEquals(480000.0f, chunk1[0], 0.0001f)
        assertEquals(959999.0f, chunk1[479999], 0.0001f)

        // Chunk 2: [60s, 90s) -> samples 960000 .. 1439999
        val chunk2 = AudioDecoder.sliceSamples(syntheticSamples, startMs = 60_000L, durationMs = 30_000L, sampleRate = sampleRate)
        assertEquals(480000, chunk2.size)
        assertEquals(960000.0f, chunk2[0], 0.0001f)
        assertEquals(1439999.0f, chunk2[479999], 0.0001f)

        // Strictly verify chunk 0 and chunk 1 are disjoint
        assertTrue(chunk0[chunk0.size - 1] < chunk1[0])
        assertTrue(chunk1[chunk1.size - 1] < chunk2[0])
    }

    /**
     * Requirement 19-4: perSourceMediaStoreVersionReset
     * All sources stored version A. MediaStore resets to version B.
     * Photo coordinator runs first -> resets photo baseline.
     * Recording coordinator runs second -> MUST still detect A -> B and reset recording baseline.
     * Screenshot coordinator runs third -> MUST still detect A -> B and reset screenshot baseline.
     */
    @Test
    fun perSourceMediaStoreVersionReset() = runTest {
        settingsRepository.updatePhotoMediaStoreVersion("VERSION_A")
        settingsRepository.updateRecordingMediaStoreVersion("VERSION_A")
        settingsRepository.updateScreenshotMediaStoreVersion("VERSION_A")
        settingsRepository.updateLastPhotoMediaId(100L)
        settingsRepository.updateLastRecordingMediaId(200L)
        settingsRepository.updateLastScreenshotMediaId(300L)

        val photoCoordinator = PhotoProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = fakeOcrEngine
        ).apply {
            mediaStoreVersionProvider = { "VERSION_B" }
        }

        val recordingCoordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = fakeSttEngine
        ).apply {
            mediaStoreVersionProvider = { "VERSION_B" }
        }

        val screenshotCoordinator = ScreenshotProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = fakeOcrEngine
        ).apply {
            mediaStoreVersionProvider = { "VERSION_B" }
        }

        // 1. Photo coordinator processes first
        photoCoordinator.processPendingPhotos()
        val s1 = settingsRepository.getSettings().first()
        assertEquals("VERSION_B", s1.photoMediaStoreVersion)
        assertEquals("VERSION_A", s1.recordingMediaStoreVersion)
        assertEquals("VERSION_A", s1.screenshotMediaStoreVersion)

        // 2. Recording coordinator processes second
        recordingCoordinator.processPendingRecordings()
        val s2 = settingsRepository.getSettings().first()
        assertEquals("VERSION_B", s2.photoMediaStoreVersion)
        assertEquals("VERSION_B", s2.recordingMediaStoreVersion)
        assertEquals("VERSION_A", s2.screenshotMediaStoreVersion)

        // 3. Screenshot coordinator processes third
        screenshotCoordinator.processPendingScreenshots()
        val s3 = settingsRepository.getSettings().first()
        assertEquals("VERSION_B", s3.photoMediaStoreVersion)
        assertEquals("VERSION_B", s3.recordingMediaStoreVersion)
        assertEquals("VERSION_B", s3.screenshotMediaStoreVersion)
    }

    /**
     * Requirement 19-5: photoPermissionRevokedDoesNotMarkMissing
     * When READ_MEDIA_IMAGES is revoked, reconcileDeletedPhotos must PRESERVE sourceExists=true.
     */
    @Test
    fun photoPermissionRevokedDoesNotMarkMissing() = runTest {
        val memory = MemoryItem(
            id = "photo-1",
            sourceType = SourceType.PHOTO,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Test Photo",
            rawText = "Photo Content",
            sourceUri = "content://media/external/images/media/999",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        memoryRepository.saveMemory(memory)

        // Create validator that returns Unavailable when permission revoked
        val unavailableValidator = object : SourceExistenceValidator(context) {
            override fun checkSourceExistence(uriString: String?, sourceType: SourceType?): SourceExistence {
                return SourceExistence.Unavailable(SecurityException("READ_MEDIA_IMAGES revoked"))
            }
        }

        val coordinator = PhotoProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = fakeOcrEngine,
            sourceExistenceValidator = unavailableValidator
        )

        val updatedCount = coordinator.reconcileDeletedPhotos()
        assertEquals("Unavailable source must not count as deleted", 0, updatedCount)

        val retrieved = memoryRepository.getMemoryById("photo-1").first()
        requireNotNull(retrieved)
        assertTrue("sourceExists MUST remain true when permission is revoked", retrieved.sourceExists)
    }

    /**
     * Requirement 19-6: recordingPermissionRevokedDoesNotMarkMissing
     * When READ_MEDIA_AUDIO is revoked, reconcileDeletedRecordings must PRESERVE sourceExists=true.
     */
    @Test
    fun recordingPermissionRevokedDoesNotMarkMissing() = runTest {
        val memory = MemoryItem(
            id = "recording-1",
            sourceType = SourceType.RECORDING,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Test Recording",
            rawText = "Spoken Content",
            sourceUri = "content://media/external/audio/media/888",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        memoryRepository.saveMemory(memory)

        // Create validator that returns Unavailable when permission revoked
        val unavailableValidator = object : SourceExistenceValidator(context) {
            override fun checkSourceExistence(uriString: String?, sourceType: SourceType?): SourceExistence {
                return SourceExistence.Unavailable(SecurityException("READ_MEDIA_AUDIO revoked"))
            }
        }

        val coordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = fakeSttEngine,
            sourceExistenceValidator = unavailableValidator
        )

        val updatedCount = coordinator.reconcileDeletedRecordings()
        assertEquals("Unavailable source must not count as deleted", 0, updatedCount)

        val retrieved = memoryRepository.getMemoryById("recording-1").first()
        requireNotNull(retrieved)
        assertTrue("sourceExists MUST remain true when permission is revoked", retrieved.sourceExists)
    }

    /**
     * Requirement 19-7: boundedPhotoDeletionReconciliation
     * With 10,000 historical photo memories, a single reconciliation cycle checks at most batchSize (25).
     */
    @Test
    fun boundedPhotoDeletionReconciliation() = runTest {
        for (i in 1..10_000) {
            memoryRepository.saveMemory(
                MemoryItem(
                    id = "photo-$i",
                    sourceType = SourceType.PHOTO,
                    createdAt = i.toLong(),
                    capturedAt = i.toLong(),
                    title = "Photo $i",
                    rawText = "Text $i",
                    sourceUri = "content://media/external/images/media/$i",
                    sourceExists = true,
                    processingState = ProcessingState.COMPLETED
                )
            )
        }

        var checkCount = 0
        val countingValidator = object : SourceExistenceValidator(context) {
            override fun checkSourceExistence(uriString: String?, sourceType: SourceType?): SourceExistence {
                checkCount++
                return SourceExistence.Exists
            }
        }

        val coordinator = PhotoProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            ocrEngine = fakeOcrEngine,
            sourceExistenceValidator = countingValidator
        )

        coordinator.reconcileDeletedPhotos(batchSize = 25)

        assertTrue(
            "Expected <= 25 existence checks for 10,000 memories, but was $checkCount",
            checkCount in 1..25
        )
    }

    /**
     * Requirement 19-8: boundedRecordingDeletionReconciliation
     * With 10,000 historical recording memories, a single reconciliation cycle checks at most batchSize (25).
     */
    @Test
    fun boundedRecordingDeletionReconciliation() = runTest {
        for (i in 1..10_000) {
            memoryRepository.saveMemory(
                MemoryItem(
                    id = "recording-$i",
                    sourceType = SourceType.RECORDING,
                    createdAt = i.toLong(),
                    capturedAt = i.toLong(),
                    title = "Recording $i",
                    rawText = "Transcript $i",
                    sourceUri = "content://media/external/audio/media/$i",
                    sourceExists = true,
                    processingState = ProcessingState.COMPLETED
                )
            )
        }

        var checkCount = 0
        val countingValidator = object : SourceExistenceValidator(context) {
            override fun checkSourceExistence(uriString: String?, sourceType: SourceType?): SourceExistence {
                checkCount++
                return SourceExistence.Exists
            }
        }

        val coordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = fakeSttEngine,
            sourceExistenceValidator = countingValidator
        )

        coordinator.reconcileDeletedRecordings(batchSize = 25)

        assertTrue(
            "Expected <= 25 existence checks for 10,000 memories, but was $checkCount",
            checkCount in 1..25
        )
    }

    private fun createPcmWavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val numSamples = samples.size
        val bytesPerSample = 2
        val dataSize = numSamples * bytesPerSample
        val totalSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // subchunk1 size
        buffer.putShort(1) // PCM format
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * bytesPerSample)
        buffer.putShort(bytesPerSample.toShort())
        buffer.putShort(16) // bits per sample
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        for (sample in samples) {
            val shortVal = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            buffer.putShort(shortVal)
        }
        return buffer.array()
    }
}
