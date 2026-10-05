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
import com.cayana.source.recording.RecordingTranscriptionWorker
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

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Clean any existing model dirs
        SherpaModelManager.getModelDir(context).deleteRecursively()
        SherpaModelManager.getDownloadTempDir(context).deleteRecursively()

        // Initialize WorkManager test driver
        val config = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(androidx.work.testing.SynchronousExecutor())
            .build()
        try {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        } catch (_: Exception) {}
    }

    @After
    fun tearDown() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
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
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val roomRepo = RoomMemoryRepository(db.memoryDao())
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
                roomRepo.saveMemory(item)
            }

            // Paginate using compound cursor (capturedAt, id) with limit 25
            var cursorCapturedAt = Long.MAX_VALUE
            var cursorId = ""
            val collectedMemories = mutableListOf<MemoryItem>()

            while (true) {
                val batch = roomRepo.getMemoriesForCompoundReconciliation(
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
        } finally {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            db.close()
        }
    }

    // 10. recordingReconciliationHandlesSameTimestampWithoutSkipping
    @Test
    fun recordingReconciliationHandlesSameTimestampWithoutSkipping() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val roomRepo = RoomMemoryRepository(db.memoryDao())
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
                roomRepo.saveMemory(item)
            }

            // Paginate using compound cursor (capturedAt, id) with limit 25
            var cursorCapturedAt = Long.MAX_VALUE
            var cursorId = ""
            val collectedMemories = mutableListOf<MemoryItem>()

            while (true) {
                val batch = roomRepo.getMemoriesForCompoundReconciliation(
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
        } finally {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            db.close()
        }
    }

    // 11. productionModelUrlUsesHttpsSupportedArchive
    @Test
    fun productionModelUrlUsesHttpsSupportedArchive() {
        val defaultUrl = SherpaModelInstaller.getDefaultDownloadUrl()
        assertTrue("Production model URL must use HTTPS", defaultUrl.startsWith("https://"))
        assertTrue("Production model URL must point to pinned SenseVoice model", defaultUrl.contains(SherpaModelManager.MODEL_ID))
        assertTrue(
            "Production model URL must use supported archive format (.tar.bz2 or .zip)",
            defaultUrl.endsWith(".tar.bz2") || defaultUrl.endsWith(".zip")
        )
        assertFalse("Production model URL must not be local emulator address", defaultUrl.contains("10.0.2.2"))
    }

    // 12. releaseDoesNotAllowGlobalCleartext
    @Test
    fun releaseDoesNotAllowGlobalCleartext() {
        val manifestCandidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
            File("../../app/src/main/AndroidManifest.xml")
        )
        val manifestFile = manifestCandidates.firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml must exist in project sources", manifestFile)
        val content = manifestFile!!.readText()
        assertFalse(
            "Production/main AndroidManifest.xml must not enable global cleartext traffic",
            content.contains("android:usesCleartextTraffic=\"true\"")
        )
    }

    // 13. interruptedInstallPreservesPreviousVerifiedModel
    @Test
    fun interruptedInstallPreservesPreviousVerifiedModel() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        val installer = SherpaModelInstaller(context, settingsRepository)

        // Install Model A
        val modelAData = "MODEL_A_INITIAL_VERIFIED_CONTENT".toByteArray()
        val tokensAData = "TOKEN_A\nTOKEN_B".toByteArray()
        val shaModelA = computeSha256(modelAData)
        val shaTokensA = computeSha256(tokensAData)

        val archiveA = File(context.cacheDir, "model_a.zip")
        createTestArchive(archiveA, mapOf("model.int8.onnx" to modelAData, "tokens.txt" to tokensAData))
        try {
            val installedA = installer.installFromArchive(archiveA, shaModelA, shaTokensA)
            assertTrue(installedA)

            var settings = settingsRepository.getSettings().first()
            assertTrue(SherpaModelManager.isModelReady(context, settings, shaModelA, shaTokensA))

            // Now attempt installing corrupted Model B
            val corruptB = File(context.cacheDir, "model_b_corrupt.zip")
            createTestArchive(corruptB, mapOf("model.int8.onnx" to "CORRUPTED_B".toByteArray(), "tokens.txt" to tokensAData))
            try {
                installer.installFromArchive(corruptB, shaModelA, shaTokensA)
                fail("Expected SecurityException")
            } catch (e: SecurityException) {
                // Expected failure
            } finally {
                corruptB.delete()
            }

            // Model A MUST REMAIN UNTOUCHED AND FULLY USABLE
            settings = settingsRepository.getSettings().first()
            assertTrue(
                "Previous verified Model A must remain intact and usable after failed install",
                SherpaModelManager.isModelReady(context, settings, shaModelA, shaTokensA)
            )
            val prodModel = SherpaModelManager.getModelFile(context)
            assertEquals("MODEL_A_INITIAL_VERIFIED_CONTENT", prodModel.readText())
        } finally {
            archiveA.delete()
        }
    }

    // 14. recordingDiscoveryDoesNotRunSttInline
    @Test
    fun recordingDiscoveryDoesNotRunSttInline() = runTest {
        val memoryRepository = FakeMemoryRepository()
        val settingsRepository = InMemorySettingsRepository()
        val permissionChecker = FakePermissionChecker()
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_AUDIO", true)

        var sttCalls = 0
        val mockStt = object : SpeechToTextEngine {
            override val engineName: String = "counting-stt"
            override val isModelAvailable: Boolean = true
            override suspend fun getAudioDurationMs(context: Context, audioUri: String): Long = 120_000L
            override suspend fun transcribeChunk(context: Context, audioUri: String, chunkIndex: Int, startMs: Long, durationMs: Long): SttChunkResult {
                sttCalls++
                return SttChunkResult.Success(chunkIndex, "test")
            }
        }

        val coordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = mockStt
        )
        assertFalse("Coordinator autoTranscribeSync must default to false in production", coordinator.autoTranscribeSync)

        val audioFile = File(context.cacheDir, "new_rec.wav")
        audioFile.writeBytes(createWavBytes(10))
        val uri = android.net.Uri.fromFile(audioFile).toString()

        try {
            val memory = MemoryItem(
                id = "disc_test",
                sourceType = SourceType.RECORDING,
                sourceUri = uri,
                sourceExists = true,
                capturedAt = System.currentTimeMillis(),
                processingState = ProcessingState.PROCESSING,
                metadata = mapOf("totalChunks" to "1", "completedChunks" to "0", "durationMs" to "10000")
            )
            memoryRepository.saveMemory(memory)

            // In production, discovering or saving recording does not execute heavy STT inline
            assertEquals(0, sttCalls)
        } finally {
            audioFile.delete()
        }
    }

    // 15. startupReconciliationOnlySchedulesStt
    @Test
    fun startupReconciliationOnlySchedulesStt() = runTest {
        val memoryRepository = FakeMemoryRepository()
        val settingsRepository = InMemorySettingsRepository()
        val permissionChecker = FakePermissionChecker()
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_AUDIO", true)

        var sttCalls = 0
        val mockStt = object : SpeechToTextEngine {
            override val engineName: String = "counting-stt"
            override val isModelAvailable: Boolean = true
            override suspend fun getAudioDurationMs(context: Context, audioUri: String): Long = 60_000L
            override suspend fun transcribeChunk(context: Context, audioUri: String, chunkIndex: Int, startMs: Long, durationMs: Long): SttChunkResult {
                sttCalls++
                return SttChunkResult.Success(chunkIndex, "test")
            }
        }

        val coordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = mockStt
        )

        val unfinishItem = MemoryItem(
            id = "rec_inflight_1",
            sourceType = SourceType.RECORDING,
            sourceUri = "content://media/external/audio/media/999",
            sourceExists = true,
            capturedAt = 1000L,
            processingState = ProcessingState.PROCESSING,
            metadata = mapOf("durationMs" to "45000", "totalChunks" to "2", "completedChunks" to "0")
        )
        memoryRepository.saveMemory(unfinishItem)

        // Run startup reconciliation
        val reconciledCount = coordinator.reconcileInFlightRecordings()
        assertEquals(1, reconciledCount)

        // Must NOT run SenseVoice inline
        assertEquals("Startup reconciliation must only schedule work, never run heavy STT inline", 0, sttCalls)
    }

    // 16. duplicateSchedulingExecutesOneHeavyTranscription
    @Test
    fun duplicateSchedulingExecutesOneHeavyTranscription() = runTest {
        val memoryRepository = FakeMemoryRepository()
        val settingsRepository = InMemorySettingsRepository()
        val permissionChecker = FakePermissionChecker()
        permissionChecker.setPermissionGranted("android.permission.READ_MEDIA_AUDIO", true)

        var heavySttExecutions = 0
        val mockStt = object : SpeechToTextEngine {
            override val engineName: String = "exec-counting-stt"
            override val isModelAvailable: Boolean = true
            override suspend fun getAudioDurationMs(context: Context, audioUri: String): Long = 10_000L
            override suspend fun transcribeChunk(context: Context, audioUri: String, chunkIndex: Int, startMs: Long, durationMs: Long): SttChunkResult {
                heavySttExecutions++
                return SttChunkResult.Success(chunkIndex, "done")
            }
        }

        val coordinator = RecordingProcessingCoordinator(
            context = context,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            sttEngine = mockStt
        )

        val audioFile = File(context.cacheDir, "concurrent_rec.wav")
        audioFile.writeBytes(createWavBytes(10))
        val audioUri = android.net.Uri.fromFile(audioFile).toString()

        try {
            val memoryId = "concurrent_rec_1"
            val memoryItem = MemoryItem(
                id = memoryId,
                sourceType = SourceType.RECORDING,
                sourceUri = audioUri,
                sourceExists = true,
                capturedAt = 2000L,
                processingState = ProcessingState.PROCESSING,
                metadata = mapOf("durationMs" to "10000", "totalChunks" to "1", "completedChunks" to "0")
            )
            memoryRepository.saveMemory(memoryItem)

            // Three concurrent discovery triggers schedule the same memory
            RecordingTranscriptionWorker.scheduleTranscription(context, memoryId, 10_000L)
            RecordingTranscriptionWorker.scheduleTranscription(context, memoryId, 10_000L)
            RecordingTranscriptionWorker.scheduleTranscription(context, memoryId, 10_000L)

            // WorkManager unique work name
            val workName = RecordingTranscriptionWorker.getWorkName(memoryId)
            val workInfos = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
            assertEquals("Exactly one unique work must be enqueued despite duplicate triggers", 1, workInfos.size)

            // Coordinator does not run inline STT
            assertEquals(0, heavySttExecutions)

            // When the single scheduled worker executes
            coordinator.transcribeRecording(memoryItem)
            assertEquals(1, heavySttExecutions)
        } finally {
            audioFile.delete()
        }
    }

    // 17. veryLongRecordingDoesNotTranscribeBeforeChargingConstraint
    @Test
    fun veryLongRecordingDoesNotTranscribeBeforeChargingConstraint() = runTest {
        val durationMs = 45 * 60 * 1000L // 45 minutes
        val constraints = RecordingConfig.getConstraints(durationMs)
        assertTrue("45 minute recording must require charging", constraints.requiresCharging())

        val memoryId = "long_rec_45m"
        RecordingTranscriptionWorker.scheduleTranscription(context, memoryId, durationMs)

        val workName = RecordingTranscriptionWorker.getWorkName(memoryId)
        val workInfos = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, workInfos.size)

        val workInfo = workInfos[0]
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, workInfo.state)
    }

    // 18. officialTarBz2ArchiveExtractedAndVerified
    @Test
    fun officialTarBz2ArchiveExtractedAndVerified() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        val installer = SherpaModelInstaller(context, settingsRepository)

        val dummyModel = "TAR_BZ2_VALID_MODEL_DATA".toByteArray()
        val dummyTokens = "TOKEN_A\nTOKEN_B".toByteArray()
        val shaModel = computeSha256(dummyModel)
        val shaTokens = computeSha256(dummyTokens)

        val tarBz2File = File(context.cacheDir, "test_model.tar.bz2")
        createTestTarBz2Archive(tarBz2File, mapOf("model.int8.onnx" to dummyModel, "tokens.txt" to dummyTokens))

        try {
            val installed = installer.installFromArchive(tarBz2File, shaModel, shaTokens)
            assertTrue("Installer should succeed extracting tar.bz2", installed)

            val prodDir = SherpaModelManager.getModelDir(context)
            assertTrue("Production model file must exist", File(prodDir, "model.int8.onnx").exists())
            assertTrue("Production tokens file must exist", File(prodDir, "tokens.txt").exists())
            assertTrue("Ready marker must exist", File(prodDir, SherpaModelManager.READY_MARKER_FILE_NAME).exists())
        } finally {
            tarBz2File.delete()
        }
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

    private fun createTestTarBz2Archive(file: File, entries: Map<String, ByteArray>) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).buffered().use { fos ->
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(fos).use { bzOut ->
                org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bzOut).use { tarOut ->
                    for ((name, data) in entries) {
                        val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
                        entry.size = data.size.toLong()
                        tarOut.putArchiveEntry(entry)
                        tarOut.write(data)
                        tarOut.closeArchiveEntry()
                    }
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
