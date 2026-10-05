package com.cayana.source

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.processing.SpeechToTextEngine
import com.cayana.processing.SttChunkResult
import com.cayana.processing.stt.AudioDecoder
import com.cayana.processing.stt.SherpaModelInstaller
import com.cayana.processing.stt.SherpaModelManager
import com.cayana.processing.stt.SherpaOnnxSttEngine
import com.cayana.source.recording.RecordingConfig
import com.cayana.source.recording.RecordingProcessingCoordinator
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage4FinalAcceptanceTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var roomMemoryRepository: RoomMemoryRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        roomMemoryRepository = RoomMemoryRepository(database.memoryDao())

        // Clean any existing model dirs
        SherpaModelManager.getModelDir(context).deleteRecursively()
        SherpaModelManager.getDownloadTempDir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        database.close()
        SherpaModelManager.getModelDir(context).deleteRecursively()
        SherpaModelManager.getDownloadTempDir(context).deleteRecursively()
    }

    // 1. freshInstallModelUnavailableUntilInstalled
    @Test
    fun freshInstallModelUnavailableUntilInstalled() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        assertFalse(
            "Model should not be ready on fresh install",
            SherpaModelManager.isModelReady(context, settingsRepository.getSettings().first())
        )

        val engine = SherpaOnnxSttEngine(context)
        assertFalse(
            "Engine isModelAvailable must be false on fresh install",
            engine.isModelAvailable
        )
    }

    // 2. modelChecksumMismatchRejected
    @Test
    fun modelChecksumMismatchRejected() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        val installer = SherpaModelInstaller(context, settingsRepository)

        val corruptArchive = File(context.cacheDir, "test_corrupt.zip")
        createTestArchive(
            corruptArchive,
            mapOf(
                "model.int8.onnx" to "CORRUPTED_MODEL_CONTENT".toByteArray(),
                "tokens.txt" to "DUMMY_TOKENS".toByteArray()
            )
        )

        try {
            // Using official expected SHA-256 or mismatched expected hash
            installer.installFromArchive(
                archiveFile = corruptArchive,
                expectedModelSha256 = SherpaModelManager.EXPECTED_MODEL_SHA256,
                expectedTokensSha256 = SherpaModelManager.EXPECTED_TOKENS_SHA256
            )
            fail("Expected SecurityException on checksum mismatch")
        } catch (e: SecurityException) {
            assertTrue("Exception message should report checksum mismatch", e.message?.contains("checksum") == true)
        } finally {
            corruptArchive.delete()
        }

        // Verify temp directory was wiped
        val tempDir = SherpaModelManager.getDownloadTempDir(context)
        assertTrue("Temporary dir should be deleted after failure", !tempDir.exists() || tempDir.listFiles().isNullOrEmpty())

        // Verify production model directory has no model files
        val prodDir = SherpaModelManager.getModelDir(context)
        assertFalse("Production model must not exist after failed install", File(prodDir, "model.int8.onnx").exists())
    }

    // 3. verifiedModelBecomesAvailable
    @Test
    fun verifiedModelBecomesAvailable() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        val installer = SherpaModelInstaller(context, settingsRepository)

        val dummyModelContent = "VALID_MODEL_INT8_DATA_SAMPLE".toByteArray()
        val dummyTokensContent = "TOKEN1\nTOKEN2\nTOKEN3".toByteArray()

        val expectedModelSha = computeSha256(dummyModelContent)
        val expectedTokensSha = computeSha256(dummyTokensContent)

        val validArchive = File(context.cacheDir, "test_valid.zip")
        createTestArchive(
            validArchive,
            mapOf(
                "model.int8.onnx" to dummyModelContent,
                "tokens.txt" to dummyTokensContent
            )
        )

        try {
            val installed = installer.installFromArchive(
                archiveFile = validArchive,
                expectedModelSha256 = expectedModelSha,
                expectedTokensSha256 = expectedTokensSha
            )
            assertTrue("Installer should succeed with matching checksums", installed)

            val prodDir = SherpaModelManager.getModelDir(context)
            assertTrue("Production model file must exist", File(prodDir, "model.int8.onnx").exists())
            assertTrue("Production tokens file must exist", File(prodDir, "tokens.txt").exists())

            val settings = settingsRepository.getSettings().first()
            assertEquals(expectedModelSha, settings.verifiedModelSha256)
            assertEquals(expectedTokensSha, settings.verifiedTokensSha256)

            // Cached fast-path verification check
            assertTrue(
                "Model must be reported ready after verified installation",
                SherpaModelManager.isModelReady(context, settings, expectedModelSha, expectedTokensSha)
            )

            val engine = SherpaOnnxSttEngine(context)
            assertTrue(
                "Engine isModelAvailable must be true after verified install",
                engine.isModelReady(context, expectedModelSha, expectedTokensSha)
            )
        } finally {
            validArchive.delete()
        }
    }

    // 4. rangeDecoderDoesNotDecodeWholeLongFile
    @Test
    fun rangeDecoderDoesNotDecodeWholeLongFile() {
        val durationSeconds = 60
        val sampleRate = 16000
        val wavBytes = createWavBytes(durationSeconds = durationSeconds, sampleRate = sampleRate)

        val trackingStream = TrackingInputStream(ByteArrayInputStream(wavBytes))
        val startMs = 30_000L
        val requestedDurationMs = 10_000L

        val samples = AudioDecoder.decodeWavRange(trackingStream, startMs, requestedDurationMs)
        assertNotNull("Decoded samples should not be null", samples)

        val expectedSamples = (requestedDurationMs * sampleRate / 1000L).toInt()
        assertEquals(expectedSamples, samples!!.size)

        // 30 seconds of 16-bit mono 16kHz audio = 30 * 16000 * 2 = 960,000 bytes skipped
        assertTrue("Decoder should skip preceding audio frames directly", trackingStream.totalSkipped >= 960_000)

        // 10 seconds of 16-bit mono audio = 320,000 bytes read (+ small header)
        // Must NOT read anywhere near the full 1.92 MB file!
        assertTrue("Decoder must only read the requested range chunk into memory", trackingStream.totalRead < 350_000)
    }

    // 5. resumeStartsAtFirstUnfinishedAudioRange
    @Test
    fun resumeStartsAtFirstUnfinishedAudioRange() = runTest {
        val memoryRepository = FakeMemoryRepository()
        val settingsRepository = InMemorySettingsRepository()
        val permissionChecker = FakePermissionChecker()
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_AUDIO", true)

        val transcribedChunks = mutableListOf<Int>()
        val mockSttEngine = object : SpeechToTextEngine {
            override val engineName: String = "mock-stt"
            override val isModelAvailable: Boolean = true
            override suspend fun getAudioDurationMs(context: Context, audioUri: String): Long = 150_000L
            override suspend fun transcribeChunk(
                context: Context,
                audioUri: String,
                chunkIndex: Int,
                startMs: Long,
                durationMs: Long
            ): SttChunkResult {
                transcribedChunks.add(chunkIndex)
                return SttChunkResult.Success(chunkIndex, "resumed_chunk_$chunkIndex")
            }
        }

        val coordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = mockSttEngine
        )

        val audioFile = File(context.cacheDir, "resume_test.wav")
        audioFile.writeBytes(createWavBytes(30))
        val audioUri = android.net.Uri.fromFile(audioFile).toString()

        try {
            val existingMemory = MemoryItem(
                id = "rec_resume_test",
                sourceType = SourceType.RECORDING,
                sourceUri = audioUri,
                sourceExists = true,
                capturedAt = 1000L,
                processingState = ProcessingState.PROCESSING,
                metadata = mapOf(
                    "totalChunks" to "5",
                    "completedChunks" to "2",
                    "chunk_0" to "already_transcribed_0",
                    "chunk_1" to "already_transcribed_1",
                    "durationMs" to "${5 * RecordingConfig.CHUNK_DURATION_MS}",
                    "displayName" to "resume_test.wav"
                )
            )
            memoryRepository.saveMemory(existingMemory)

            val completed = coordinator.transcribeRecording(existingMemory)
            assertEquals(ProcessingState.COMPLETED, completed.processingState)

            // Chunks 0 and 1 must NOT have been called on engine!
            assertFalse("Chunk 0 must not be re-decoded or re-transcribed", transcribedChunks.contains(0))
            assertFalse("Chunk 1 must not be re-decoded or re-transcribed", transcribedChunks.contains(1))
            assertEquals(listOf(2, 3, 4), transcribedChunks)

            // Final text should contain all assembled chunks
            val assembledText = completed.rawText ?: ""
            assertTrue(assembledText.contains("already_transcribed_0"))
            assertTrue(assembledText.contains("already_transcribed_1"))
            assertTrue(assembledText.contains("resumed_chunk_2"))
            assertTrue(assembledText.contains("resumed_chunk_3"))
            assertTrue(assembledText.contains("resumed_chunk_4"))
        } finally {
            audioFile.delete()
        }
    }

    // 6. shortRecordingUsesNormalConstraint
    @Test
    fun shortRecordingUsesNormalConstraint() {
        val durationMs = 2 * 60 * 1000L // 2 minutes (< 5 min)
        val policy = RecordingConfig.getBatteryPolicy(durationMs)
        val constraints = RecordingConfig.getConstraints(durationMs)

        assertEquals(RecordingConfig.BatteryPolicy.NORMAL, policy)
        assertFalse("Short recordings should not require battery not low", constraints.requiresBatteryNotLow())
        assertFalse("Short recordings should not require charging", constraints.requiresCharging())
    }

    // 7. mediumRecordingRequiresBatteryNotLow
    @Test
    fun mediumRecordingRequiresBatteryNotLow() {
        val durationMs = 15 * 60 * 1000L // 15 minutes (5..30 min)
        val policy = RecordingConfig.getBatteryPolicy(durationMs)
        val constraints = RecordingConfig.getConstraints(durationMs)

        assertEquals(RecordingConfig.BatteryPolicy.BATTERY_NOT_LOW, policy)
        assertTrue("Medium recordings must require battery not low", constraints.requiresBatteryNotLow())
        assertFalse("Medium recordings should not require charging", constraints.requiresCharging())
    }

    // 8. veryLongRecordingRequiresCharging
    @Test
    fun veryLongRecordingRequiresCharging() {
        val durationMs = 45 * 60 * 1000L // 45 minutes (> 30 min)
        val policy = RecordingConfig.getBatteryPolicy(durationMs)
        val constraints = RecordingConfig.getConstraints(durationMs)

        assertEquals(RecordingConfig.BatteryPolicy.CHARGING, policy)
        assertTrue("Very long recordings must require charging", constraints.requiresCharging())
    }

    // 9. photoReconciliationHandlesSameTimestampWithoutSkipping
    @Test
    fun photoReconciliationHandlesSameTimestampWithoutSkipping() = runTest {
        val sameCapturedAt = 1_700_000_000_000L

        // Insert 100 photo memories with exact same capturedAt and distinct IDs
        for (i in 1..100) {
            val id = "photo_%03d".format(i)
            val item = MemoryItem(
                id = id,
                sourceType = SourceType.PHOTO,
                sourceUri = "cayana://test/photo/$id.jpg",
                sourceExists = true,
                capturedAt = sameCapturedAt,
                processingState = ProcessingState.COMPLETED
            )
            roomMemoryRepository.saveMemory(item)
        }

        // Paginate using compound cursor (capturedAt, id) with limit 25
        var cursorCapturedAt = Long.MAX_VALUE
        var cursorId = ""
        val collectedMemories = mutableListOf<MemoryItem>()

        while (true) {
            val batch = roomMemoryRepository.getMemoriesForCompoundReconciliation(
                sourceType = SourceType.PHOTO,
                cursorCapturedAt = cursorCapturedAt,
                cursorId = cursorId,
                limit = 25
            )
            if (batch.isEmpty()) break
            collectedMemories.addAll(batch)
            val last = batch.last()
            cursorCapturedAt = last.capturedAt
            cursorId = last.id
        }

        assertEquals("Should retrieve all 100 memories across 4 batches of 25", 100, collectedMemories.size)
        val uniqueIds = collectedMemories.map { it.id }.toSet()
        assertEquals("All 100 retrieved memories must have unique IDs (0 skipped, 0 duplicate)", 100, uniqueIds.size)
    }

    // 10. recordingReconciliationHandlesSameTimestampWithoutSkipping
    @Test
    fun recordingReconciliationHandlesSameTimestampWithoutSkipping() = runTest {
        val sameCapturedAt = 1_700_000_000_000L

        // Insert 100 recording memories with exact same capturedAt and distinct IDs
        for (i in 1..100) {
            val id = "rec_%03d".format(i)
            val item = MemoryItem(
                id = id,
                sourceType = SourceType.RECORDING,
                sourceUri = "cayana://test/audio/$id.m4a",
                sourceExists = true,
                capturedAt = sameCapturedAt,
                processingState = ProcessingState.COMPLETED
            )
            roomMemoryRepository.saveMemory(item)
        }

        // Paginate using compound cursor (capturedAt, id) with limit 25
        var cursorCapturedAt = Long.MAX_VALUE
        var cursorId = ""
        val collectedMemories = mutableListOf<MemoryItem>()

        while (true) {
            val batch = roomMemoryRepository.getMemoriesForCompoundReconciliation(
                sourceType = SourceType.RECORDING,
                cursorCapturedAt = cursorCapturedAt,
                cursorId = cursorId,
                limit = 25
            )
            if (batch.isEmpty()) break
            collectedMemories.addAll(batch)
            val last = batch.last()
            cursorCapturedAt = last.capturedAt
            cursorId = last.id
        }

        assertEquals("Should retrieve all 100 memories across 4 batches of 25", 100, collectedMemories.size)
        val uniqueIds = collectedMemories.map { it.id }.toSet()
        assertEquals("All 100 retrieved memories must have unique IDs (0 skipped, 0 duplicate)", 100, uniqueIds.size)
    }

    // --- Helpers ---

    private fun createTestArchive(file: File, entries: Map<String, ByteArray>) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zos ->
                for ((name, data) in entries) {
                    val entry = ZipEntry(name)
                    zos.putNextEntry(entry)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun createWavBytes(durationSeconds: Int, sampleRate: Int = 16000): ByteArray {
        val totalSamples = durationSeconds * sampleRate
        val dataSize = totalSamples * 2
        val totalSize = 36 + dataSize
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray())

        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // Mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)

        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        for (i in 0 until totalSamples) {
            val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000).toInt().toShort()
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    private class TrackingInputStream(private val delegate: InputStream) : FilterInputStream(delegate) {
        var totalRead: Long = 0L
        var totalSkipped: Long = 0L

        override fun read(): Int {
            val r = super.read()
            if (r != -1) totalRead++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) totalRead += r
            return r
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            totalSkipped += s
            return s
        }
    }
}
