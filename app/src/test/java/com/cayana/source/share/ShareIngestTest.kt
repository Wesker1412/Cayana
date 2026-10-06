package com.cayana.source.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.Result
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.processing.OcrResult
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import com.cayana.test.FakeMediaContentProvider
import com.cayana.test.FakeOcrEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room
import com.cayana.memory.data.CayanaDatabase
import com.cayana.source.share.data.ShareReceiptEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIngestTest {

    private lateinit var context: Context
    private lateinit var repository: FakeMemoryRepository
    private lateinit var fakeOcrEngine: FakeOcrEngine
    private lateinit var shareProcessor: ShareProcessor
    private lateinit var fakeProvider: FakeMediaContentProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = FakeMemoryRepository()
        fakeOcrEngine = FakeOcrEngine()
        shareProcessor = ShareProcessor(context, repository, fakeOcrEngine)
        fakeProvider = FakeMediaContentProvider.register(context)
    }

    @Test
    fun sharePlainTextCreatesMemory() = runBlocking {
        val text = "記得下週找小王確認報價"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        val result = shareProcessor.processIntent(intent)
        assertTrue("Sharing plain text must succeed", result is ShareIngestResult.Success)

        val memories = repository.getAllMemories().first()
        assertEquals(1, memories.size)
        val memory = memories.first()
        assertEquals(SourceType.SHARED_TEXT, memory.sourceType)
        assertEquals(text, memory.rawText)
        assertEquals(text, memory.normalizedText)
        assertEquals(text, memory.title)
        assertTrue(memory.sourceExists)
        assertEquals(ProcessingState.COMPLETED, memory.processingState)
    }

    @Test
    fun shareSingleUrlCreatesUrlMemory() = runBlocking {
        val url = "https://github.com/Wesker1412/Cayana"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val result = shareProcessor.processIntent(intent)
        assertTrue("Sharing single URL must succeed", result is ShareIngestResult.Success)

        val memories = repository.getAllMemories().first()
        assertEquals(1, memories.size)
        val memory = memories.first()
        assertEquals(SourceType.SHARED_URL, memory.sourceType)
        assertEquals(url, memory.sourceUrl)
        assertEquals("GitHub", memory.title)
        assertEquals("github.com", memory.metadata["host"])
        assertTrue(memory.sourceExists)
        assertEquals(ProcessingState.COMPLETED, memory.processingState)
    }

    @Test
    fun shareTextContainingUrlPreservesBoth() = runBlocking {
        val content = "推薦這個景點 https://www.google.com/maps?q=taipei101 真的很棒"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }

        val result = shareProcessor.processIntent(intent)
        assertTrue(result is ShareIngestResult.Success)

        val memories = repository.getAllMemories().first()
        val memory = memories.first()
        assertEquals(SourceType.SHARED_URL, memory.sourceType)
        assertEquals(content, memory.rawText)
        assertEquals("https://www.google.com/maps?q=taipei101", memory.sourceUrl)
        assertEquals("Google", memory.title)
        assertEquals("www.google.com", memory.metadata["host"])
    }

    @Test
    fun shareImageRunsExistingOcr() = runBlocking {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "receipt.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.SIZE, 1024L)
        }
        val insertedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!

        fakeOcrEngine.simulatedResult = Result.Success(OcrResult("星巴克 拿鐵 NT$150"))

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, insertedUri)
        }

        val result = shareProcessor.processIntent(intent)
        assertTrue(result is ShareIngestResult.Success)

        val memories = repository.getAllMemories().first()
        val memory = memories.first()
        assertEquals(SourceType.SHARED_IMAGE, memory.sourceType)
        assertEquals("星巴克 拿鐵 NT$150", memory.rawText)
        assertEquals("receipt.jpg", memory.title)
        assertEquals(ProcessingState.COMPLETED, memory.processingState)
        assertTrue(memory.sourceExists)
    }

    @Test
    fun sharePdfWithoutParserStillCreatesMemory() = runBlocking {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "contract.pdf")
            put(MediaStore.Images.Media.MIME_TYPE, "application/pdf")
            put(MediaStore.Images.Media.SIZE, 2048L)
        }
        val insertedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, insertedUri)
        }

        val result = shareProcessor.processIntent(intent)
        assertTrue(result is ShareIngestResult.Success)

        val memories = repository.getAllMemories().first()
        val memory = memories.first()
        assertEquals(SourceType.SHARED_DOCUMENT, memory.sourceType)
        assertEquals("contract.pdf", memory.title)
        assertEquals(ProcessingState.COMPLETED_WITHOUT_TEXT, memory.processingState)
        assertTrue("PDF memory must still exist even without PDF text parser", memory.sourceExists)
    }

    @Test
    fun malformedShareDoesNotCrash() = runBlocking {
        // 1. Missing action
        val noActionIntent = Intent()
        val res1 = shareProcessor.processIntent(noActionIntent)
        assertTrue(res1 is ShareIngestResult.Ignored)

        // 2. Empty text and streams
        val emptyIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
        }
        val res2 = shareProcessor.processIntent(emptyIntent)
        assertTrue(res2 is ShareIngestResult.Ignored)

        // 3. Unsafe file scheme
        val fileUri = Uri.parse("file:///data/local/tmp/secret.txt")
        val fileIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, fileUri)
        }
        val res3 = shareProcessor.processIntent(fileIntent)
        assertTrue("Rejecting unsafe scheme must not crash", res3 is ShareIngestResult.Ignored)
    }

    @Test
    fun unsupportedMimeDoesNotIngest() = runBlocking {
        val audioIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_TEXT, "Some text with audio mime")
        }

        val result = shareProcessor.processIntent(audioIntent)
        assertTrue("Audio MIME is unsupported for Sharesheet", result is ShareIngestResult.Ignored)
        assertTrue(repository.getAllMemories().first().isEmpty())
    }

    @Test
    fun duplicateIntentDeliveryCreatesOneMemory() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "快速連按分享兩次")
        }

        val firstResult = shareProcessor.processIntent(intent)
        assertTrue(firstResult is ShareIngestResult.Success)

        val secondResult = shareProcessor.processIntent(intent)
        assertTrue("Duplicate dispatch within 60s must return Duplicate", secondResult is ShareIngestResult.Duplicate)

        // Ensure only one memory exists in storage
        val memories = repository.getAllMemories().first()
        assertEquals(1, memories.size)
    }

    @Test
    fun ephemeralUriTempFileRemovedAfterOcr() = runBlocking {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "temp_test.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.SIZE, 512L)
        }
        val insertedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, insertedUri)
        }

        shareProcessor.processIntent(intent)

        val tempDir = File(context.cacheDir, "share_temp")
        val remainingFiles = tempDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals("Temp processing files must be cleaned up in finally block", 0, remainingFiles.size)
    }

    @Test
    fun sameUrlSharedLaterCanCreateNewCapture() = runBlocking {
        val url = "https://example.com/article"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val firstResult = shareProcessor.processIntent(intent)
        assertTrue(firstResult is ShareIngestResult.Success)

        // A second processor simulates sharing in a new session or after dedup window expires
        val laterProcessor = ShareProcessor(context, repository, fakeOcrEngine)
        val secondResult = laterProcessor.processIntent(intent)
        assertTrue("Sharing same URL later can create a new capture memory", secondResult is ShareIngestResult.Success)

        val memories = repository.getAllMemories().first()
        assertEquals(2, memories.size)
        assertTrue(memories.all { it.sourceUrl == url })
    }

    @Test
    fun shareProcessorRecreatedWithinDedupWindowReturnsDuplicate() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.shareReceiptDao()
        val proc1 = ShareProcessor(context, repository, fakeOcrEngine, dao)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Durable dedup across process restart")
        }

        val res1 = proc1.processIntent(intent)
        assertTrue(res1 is ShareIngestResult.Success)

        // Simulate process recreation: proc2 is a new instance with same DB
        val proc2 = ShareProcessor(context, repository, fakeOcrEngine, dao)
        val res2 = proc2.processIntent(intent)
        assertTrue("Durable receipt must cause new processor instance to return Duplicate", res2 is ShareIngestResult.Duplicate)

        // Verify only 1 memory exists in repo
        val memories = repository.getAllMemories().first()
        assertEquals(1, memories.size)
        db.close()
    }

    @Test
    fun shareProcessDiesAfterFirstItemResumesSameSession() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.shareReceiptDao()

        val text = "Resumable session item"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        // Compute fingerprint
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(Intent.ACTION_SEND.toByteArray())
        digest.update("text/plain".toByteArray())
        digest.update(text.toByteArray())
        val fp = digest.digest().joinToString("") { "%02x".format(it) }

        val testSessionId = "resumed-session-42"
        val deterministicId = java.util.UUID.nameUUIDFromBytes("share:$testSessionId:text".toByteArray(Charsets.UTF_8)).toString()

        // Seed a PROCESSING receipt (as if process died halfway)
        val now = System.currentTimeMillis()
        dao.upsertReceipt(
            ShareReceiptEntity(
                fingerprint = fp,
                sessionId = testSessionId,
                createdAt = now,
                expiresAt = now + 60_000,
                status = "PROCESSING",
                itemIdsJson = "[]"
            )
        )

        // Also seed an incomplete / preliminary memory with the deterministic id
        val initialMemory = MemoryItem(
            id = deterministicId,
            sourceType = SourceType.SHARED_TEXT,
            createdAt = now - 500,
            capturedAt = now - 500,
            title = "Old Incomplete Title",
            rawText = text,
            normalizedText = text,
            sourceExists = true,
            processingState = ProcessingState.PENDING
        )
        repository.saveMemory(initialMemory)

        // Re-deliver intent to processor
        val proc = ShareProcessor(context, repository, fakeOcrEngine, dao)
        val res = proc.processIntent(intent)
        assertTrue(res is ShareIngestResult.Success)

        // Receipt must now be COMPLETED
        val receipt = dao.getReceipt(fp)
        assertNotNull(receipt)
        assertEquals("COMPLETED", receipt?.status)
        assertEquals(testSessionId, receipt?.sessionId)

        // Repository should still have exactly 1 memory, updated to COMPLETED with deterministic ID
        val memories = repository.getAllMemories().first()
        assertEquals(1, memories.size)
        val memory = memories.first()
        assertEquals(deterministicId, memory.id)
        assertEquals(ProcessingState.COMPLETED, memory.processingState)

        db.close()
    }

    @Test
    fun twoIdenticalSharesConcurrentProduceOneCapture() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.shareReceiptDao()
        val proc = ShareProcessor(context, repository, fakeOcrEngine, dao)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Concurrent share test text")
        }

        // Launch two concurrent ingest attempts
        val deferred1 = async(Dispatchers.IO) { proc.processIntent(intent) }
        val deferred2 = async(Dispatchers.IO) { proc.processIntent(intent) }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        val results = listOf(result1, result2)
        val successCount = results.count { it is ShareIngestResult.Success }
        val duplicateCount = results.count { it is ShareIngestResult.Duplicate }

        assertEquals("Exactly one concurrent execution must succeed", 1, successCount)
        assertEquals("The other concurrent execution must be Duplicate", 1, duplicateCount)

        val memories = repository.getAllMemories().first()
        assertEquals("Only one canonical memory item created", 1, memories.size)

        db.close()
    }

    @Test
    fun sameShareAfterExpiryAllowed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.shareReceiptDao()
        val proc = ShareProcessor(context, repository, fakeOcrEngine, dao)

        val text = "Expired share allowed again"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        // First share succeeds
        val res1 = proc.processIntent(intent)
        assertTrue(res1 is ShareIngestResult.Success)

        // Manually expire the receipt in DB
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(Intent.ACTION_SEND.toByteArray())
        digest.update("text/plain".toByteArray())
        digest.update(text.toByteArray())
        val fp = digest.digest().joinToString("") { "%02x".format(it) }

        val receipt = dao.getReceipt(fp)!!
        dao.upsertReceipt(receipt.copy(expiresAt = System.currentTimeMillis() - 10_000))

        // Same intent delivered after expiry must be treated as a fresh capture
        val res2 = proc.processIntent(intent)
        assertTrue("Sharing after dedup window expiry must succeed as a new capture", res2 is ShareIngestResult.Success)

        val memories = repository.getAllMemories().first()
        assertEquals(2, memories.size)

        db.close()
    }

    @Test
    fun processingSessionResumesAfterMoreThan60SecondsAndCompletes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.shareReceiptDao()

        val text = "Long running or interrupted share"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(Intent.ACTION_SEND.toByteArray())
        digest.update("text/plain".toByteArray())
        digest.update(text.toByteArray())
        val fp = digest.digest().joinToString("") { "%02x".format(it) }

        val interruptedSessionId = "session-interrupted-123"
        // Seed an in-flight receipt created 120 seconds ago, with expiresAt = Long.MAX_VALUE
        val twoMinutesAgo = System.currentTimeMillis() - 120_000L
        dao.upsertReceipt(
            ShareReceiptEntity(
                fingerprint = fp,
                sessionId = interruptedSessionId,
                createdAt = twoMinutesAgo,
                expiresAt = Long.MAX_VALUE,
                status = "PROCESSING",
                itemIdsJson = "[]"
            )
        )

        // Simulate app recreation / new processor instance
        val newProc = ShareProcessor(context, repository, fakeOcrEngine, dao)
        val result = newProc.processIntent(intent)

        assertTrue("Resumed processing must succeed", result is ShareIngestResult.Success)

        // Check receipt in DB: status must be COMPLETED, expiresAt within normal dedup window from now
        val receipt = dao.getReceipt(fp)
        assertNotNull(receipt)
        assertEquals("COMPLETED", receipt!!.status)
        assertEquals(interruptedSessionId, receipt.sessionId)
        assertTrue(receipt.expiresAt > System.currentTimeMillis())

        // Verify deterministic ID derived from session-interrupted-123
        val expectedMemoryId = java.util.UUID.nameUUIDFromBytes(
            "share:$interruptedSessionId:text".toByteArray(Charsets.UTF_8)
        ).toString()

        val memories = repository.getAllMemories().first()
        assertEquals(1, memories.size)
        assertEquals(expectedMemoryId, memories.first().id)

        db.close()
    }

    @Test
    fun emptyShareIntentTransitionsToTerminalIgnored() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.shareReceiptDao()
        val proc = ShareProcessor(context, repository, fakeOcrEngine, dao)

        // An intent with unsupported URI scheme that produces no valid memories
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///non_existent_or_unsafe.png"))
        }

        val result = proc.processIntent(intent)
        assertTrue("Intent with only unsafe streams must be Ignored", result is ShareIngestResult.Ignored)

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(Intent.ACTION_SEND.toByteArray())
        digest.update("image/png".toByteArray())
        digest.update("".toByteArray())
        digest.update("file:///non_existent_or_unsafe.png".toByteArray())
        val fp = digest.digest().joinToString("") { "%02x".format(it) }

        val receipt = dao.getReceipt(fp)
        assertNotNull(receipt)
        assertEquals("Receipt must transition to IGNORED terminal status", "IGNORED", receipt!!.status)
        assertTrue(receipt.expiresAt <= System.currentTimeMillis() + ShareIngestConfig.DEDUP_WINDOW_MS + 1000L)

        db.close()
    }
}
