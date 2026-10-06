package com.cayana.backup.drive

import com.cayana.core.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleDriveHttpBackupClientIntegrationTest {

    private lateinit var server: MockHttpServer
    private var serverPort: Int = 0
    private lateinit var tempDir: File
    private lateinit var stateFile: File
    private lateinit var stateStore: FileResumableUploadStateStore
    private lateinit var fakeAuthManager: FakeDriveAuthorizationManager

    @Before
    fun setUp() {
        server = MockHttpServer()
        serverPort = server.port
        tempDir = Files.createTempDirectory("cayana_backup_test").toFile()
        stateFile = File(tempDir, "resumable_state.json")
        stateStore = FileResumableUploadStateStore(stateFile)
        fakeAuthManager = FakeDriveAuthorizationManager(DriveAuthStatus.CONNECTED)
    }

    @After
    fun tearDown() {
        server.close()
        tempDir.deleteRecursively()
    }

    private fun createClient(
        chunkSize: Int = 1024 * 1024,
        threshold: Long = 1024 * 1024
    ): GoogleDriveHttpBackupClient {
        return GoogleDriveHttpBackupClient(
            driveAuthManager = fakeAuthManager,
            resumableStateStore = stateStore,
            tempDir = tempDir,
            baseDriveApi = "http://127.0.0.1:$serverPort/drive/v3/files",
            baseUploadApi = "http://127.0.0.1:$serverPort/upload/drive/v3/files",
            chunkSize = chunkSize,
            resumableThresholdBytes = threshold
        )
    }

    @Test
    fun expiredCachedToken401RefreshesAndRetriesOnce() = runTest {
        val requestCount = AtomicInteger(0)
        server.on({ _, path -> path.startsWith("/drive/v3/files") }) { req ->
            val count = requestCount.incrementAndGet()
            val auth = req.headers["authorization"]
            if (count == 1) {
                assertEquals("Bearer token_1", auth)
                MockResponse(401, emptyMap(), "{\"error\": \"invalid_token\"}")
            } else {
                assertEquals("Bearer token_2", auth)
                MockResponse(200, emptyMap(), "{\"files\": []}")
            }
        }

        fakeAuthManager.fakeAccessToken = "token_1"
        fakeAuthManager.onInvalidateToken = {
            fakeAuthManager.fakeAccessToken = "token_2"
        }

        val client = createClient()
        val result = client.listBackups()

        assertTrue("Request should succeed on retry with refreshed token", result is Result.Success)
        assertEquals(2, requestCount.get())
    }

    @Test
    fun second401BecomesAuthRequired() = runTest {
        val requestCount = AtomicInteger(0)
        server.on({ _, path -> path.startsWith("/drive/v3/files") }) { _ ->
            requestCount.incrementAndGet()
            MockResponse(401, emptyMap(), "{\"error\": \"unauthorized\"}")
        }

        val client = createClient()
        val result = client.listBackups()

        assertTrue("Second 401 must abort with error", result is Result.Error)
        assertEquals(2, requestCount.get())
        assertEquals("Status must transition to AUTH_REQUIRED", DriveAuthStatus.AUTH_REQUIRED, fakeAuthManager.authStatus.value)
    }

    @Test
    fun productionResumableUploadHandles308Range() = runTest {
        val chunkSize = 1024 * 1024 // 1 MiB
        val totalBytes = 3 * 1024 * 1024 // 3 MiB
        val data = ByteArray(totalBytes) { (it % 256).toByte() }

        val sessionPath = "/upload/session/test_308"
        server.on({ method, path -> method == "POST" && path.startsWith("/upload/drive/v3/files") }) { _ ->
            MockResponse(200, mapOf("Location" to "http://127.0.0.1:$serverPort$sessionPath"), "")
        }

        val chunkIndex = AtomicInteger(0)
        server.on({ method, path -> method == "PUT" && path == sessionPath }) { req ->
            val idx = chunkIndex.getAndIncrement()
            val contentRange = req.headers["content-range"]
            when (idx) {
                0 -> {
                    assertEquals("bytes 0-1048575/$totalBytes", contentRange)
                    MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
                }
                1 -> {
                    assertEquals("bytes 1048576-2097151/$totalBytes", contentRange)
                    MockResponse(308, mapOf("Range" to "bytes=0-2097151"))
                }
                2 -> {
                    assertEquals("bytes 2097152-3145727/$totalBytes", contentRange)
                    val response = "{\"id\":\"file_final_123\",\"name\":\"cayana-v1-test.cynb\",\"size\":\"$totalBytes\",\"createdTime\":\"2026-10-07T00:00:00Z\"}"
                    MockResponse(200, emptyMap(), response)
                }
                else -> MockResponse(500)
            }
        }

        val client = createClient(chunkSize = chunkSize)
        val result = client.uploadBackup("cayana-v1-test.cynb", data)

        if (result is Result.Error) {
            throw AssertionError("Upload failed: ${result.exception.message}", result.exception)
        }
        assertTrue("Upload must succeed through 308 range negotiations", result is Result.Success)
        val meta = (result as Result.Success).data
        assertEquals("file_final_123", meta.fileId)
        assertEquals(3, chunkIndex.get())
    }

    @Test
    fun productionResumableUploadResumesAfterConnectionDrop() = runTest {
        val chunkSize = 1024 * 1024
        val totalBytes = 3 * 1024 * 1024
        val data = ByteArray(totalBytes) { 0x42 }

        val sessionPath = "/upload/session/drop_resume"
        server.on({ method, path -> method == "POST" && path.startsWith("/upload/drive/v3/files") }) { _ ->
            MockResponse(200, mapOf("Location" to "http://127.0.0.1:$serverPort$sessionPath"), "")
        }

        val callCount = AtomicInteger(0)
        server.on({ method, path -> method == "PUT" && path == sessionPath }) { req ->
            val count = callCount.incrementAndGet()
            val rangeHeader = req.headers["content-range"]
            when (count) {
                1 -> {
                    assertEquals("bytes 0-1048575/$totalBytes", rangeHeader)
                    MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
                }
                2 -> {
                    // Fail 2nd chunk
                    MockResponse(500, emptyMap(), "Server Error")
                }
                3 -> {
                    // Status query
                    assertEquals("bytes */$totalBytes", rangeHeader)
                    MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
                }
                4 -> {
                    // Retransmit 2nd chunk
                    assertEquals("bytes 1048576-2097151/$totalBytes", rangeHeader)
                    MockResponse(308, mapOf("Range" to "bytes=0-2097151"))
                }
                5 -> {
                    // 3rd chunk completes
                    assertEquals("bytes 2097152-3145727/$totalBytes", rangeHeader)
                    val response = "{\"id\":\"file_resumed_456\",\"name\":\"cayana-v1-snap.cynb\",\"size\":\"$totalBytes\",\"createdTime\":\"2026-10-07T00:00:00Z\"}"
                    MockResponse(200, emptyMap(), response)
                }
                else -> MockResponse(500)
            }
        }

        val client = createClient(chunkSize = chunkSize)
        val firstResult = client.uploadBackup("cayana-v1-snap.cynb", data)
        assertTrue("First attempt should fail on error", firstResult is Result.Error)

        val savedState = stateStore.loadState()
        assertNotNull(savedState)
        assertEquals(1048576L, savedState?.confirmedBytes)

        val secondResult = client.uploadBackup("cayana-v1-snap.cynb", data)
        assertTrue("Second attempt should resume and succeed", secondResult is Result.Success)
        assertEquals("file_resumed_456", (secondResult as Result.Success).data.fileId)
    }

    @Test
    fun productionResumableUploadSurvivesClientRecreation() = runTest {
        val chunkSize = 1024 * 1024
        val totalBytes = 2 * 1024 * 1024
        val data = ByteArray(totalBytes) { 0x33 }

        val sessionPath = "/upload/session/client_recreation"
        server.on({ method, path -> method == "POST" && path.startsWith("/upload/drive/v3/files") }) { _ ->
            MockResponse(200, mapOf("Location" to "http://127.0.0.1:$serverPort$sessionPath"), "")
        }

        val calls = AtomicInteger(0)
        server.on({ method, path -> method == "PUT" && path == sessionPath }) { _ ->
            val c = calls.incrementAndGet()
            when (c) {
                1 -> MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
                2 -> MockResponse(503, emptyMap(), "Temporary failure")
                3 -> MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
                4 -> {
                    val response = "{\"id\":\"file_recreated_789\",\"name\":\"cayana-v1-recreate.cynb\",\"size\":\"$totalBytes\",\"createdTime\":\"2026-10-07T00:00:00Z\"}"
                    MockResponse(200, emptyMap(), response)
                }
                else -> MockResponse(500)
            }
        }

        val client1 = createClient(chunkSize = chunkSize)
        client1.uploadBackup("cayana-v1-recreate.cynb", data)

        val client2 = createClient(chunkSize = chunkSize)
        val result2 = client2.uploadBackup("cayana-v1-recreate.cynb", data)

        assertTrue(result2 is Result.Success)
        assertEquals("file_recreated_789", (result2 as Result.Success).data.fileId)
    }

    @Test
    fun expiredSession404StartsNewSession() = runTest {
        val chunkSize = 1024 * 1024
        val totalBytes = 2 * 1024 * 1024
        val data = ByteArray(totalBytes) { 0x11 }

        val session1 = "/upload/session/old_session"
        val session2 = "/upload/session/brand_new_session"

        val initCount = AtomicInteger(0)
        server.on({ method, path -> method == "POST" && path.startsWith("/upload/drive/v3/files") }) { _ ->
            val c = initCount.incrementAndGet()
            val target = if (c == 1) session1 else session2
            MockResponse(200, mapOf("Location" to "http://127.0.0.1:$serverPort$target"), "")
        }

        server.on({ _, path -> path == session1 }) { _ ->
            MockResponse(404, emptyMap(), "Not Found")
        }

        val session2Calls = AtomicInteger(0)
        server.on({ _, path -> path == session2 }) { _ ->
            val c = session2Calls.incrementAndGet()
            if (c == 1) {
                MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
            } else {
                val response = "{\"id\":\"file_brand_new_111\",\"name\":\"cayana-v1-fresh.cynb\",\"size\":\"$totalBytes\",\"createdTime\":\"2026-10-07T00:00:00Z\"}"
                MockResponse(200, emptyMap(), response)
            }
        }

        val client = createClient(chunkSize = chunkSize)
        val result = client.uploadBackup("cayana-v1-fresh.cynb", data)

        assertTrue("Upload should recover from 404 by starting a fresh session", result is Result.Success)
        assertEquals("file_brand_new_111", (result as Result.Success).data.fileId)
    }

    @Test
    fun completedUploadDeletesDurableSessionState() = runTest {
        val totalBytes = 2 * 1024 * 1024
        val data = ByteArray(totalBytes) { 0x77 }

        val sessionPath = "/upload/session/cleanup_test"
        server.on({ method, path -> method == "POST" && path.startsWith("/upload/drive/v3/files") }) { _ ->
            MockResponse(200, mapOf("Location" to "http://127.0.0.1:$serverPort$sessionPath"), "")
        }
        server.on({ method, path -> method == "PUT" && path == sessionPath }) { _ ->
            val response = "{\"id\":\"file_cleanup_ok\",\"name\":\"cayana-v1-clean.cynb\",\"size\":\"$totalBytes\",\"createdTime\":\"2026-10-07T00:00:00Z\"}"
            MockResponse(200, emptyMap(), response)
        }

        val client = createClient(chunkSize = 2 * 1024 * 1024)
        val result = client.uploadBackup("cayana-v1-clean.cynb", data)

        assertTrue(result is Result.Success)
        assertNull("Durable state file must be cleared on upload completion", stateStore.loadState())
    }

    @Test
    fun serverAcceptsFirstChunkButClientDiesBeforePersistingOffset() = runTest {
        val chunkSize = 1024 * 1024
        val totalBytes = 2 * 1024 * 1024
        val data = ByteArray(totalBytes) { 0x55 }
        val sessionPath = "/upload/session/crash_after_first_chunk"
        val fileName = "cayana-v1-crash.cynb"
        val snapshotId = java.util.UUID.nameUUIDFromBytes(fileName.toByteArray()).toString()
        val tempCiphertextFile = File(tempDir, "backup_encrypted_${snapshotId}.tmp")
        tempCiphertextFile.writeBytes(data)

        // Seed persisted state where process died before client could save confirmedBytes > 0
        stateStore.saveState(
            ResumableUploadState(
                snapshotId = snapshotId,
                encryptedTempFilePath = tempCiphertextFile.absolutePath,
                sessionUri = "http://127.0.0.1:$serverPort$sessionPath",
                totalBytes = totalBytes.toLong(),
                confirmedBytes = 0L, // Client died before persisting offset
                createdAt = System.currentTimeMillis()
            )
        )

        val serverQueries = AtomicInteger(0)
        val chunkRequests = CopyOnWriteArrayList<String>()

        server.on({ method, path -> method == "PUT" && path == sessionPath }) { req ->
            val rangeHeader = req.headers["content-range"] ?: ""
            if (rangeHeader.startsWith("bytes */")) {
                serverQueries.incrementAndGet()
                // Server tells client: First chunk (0-1048575) was actually received by server
                MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
            } else {
                chunkRequests.add(rangeHeader)
                val response = "{\"id\":\"file_crashed_offset_recovered\",\"name\":\"cayana-v1-crash.cynb\",\"size\":\"$totalBytes\",\"createdTime\":\"2026-10-07T00:00:00Z\"}"
                MockResponse(200, emptyMap(), response)
            }
        }

        // Recreate client
        val client = createClient(chunkSize = chunkSize)
        val result = client.uploadBackup("cayana-v1-crash.cynb", data)

        assertTrue("Upload must succeed", result is Result.Success)
        assertEquals(1, serverQueries.get())
        assertEquals(1, chunkRequests.size)
        // Client MUST NOT assume 0; it must start at 1048576!
        assertEquals("bytes 1048576-2097151/$totalBytes", chunkRequests[0])
    }

    @Test
    fun `308WithoutRangeDoesNotAdvanceOffset`() = runTest {
        val chunkSize = 1024 * 1024
        val totalBytes = 2 * 1024 * 1024
        val data = ByteArray(totalBytes) { 0x66 }

        val sessionPath = "/upload/session/no_range_test"
        server.on({ method, path -> method == "POST" && path.startsWith("/upload/drive/v3/files") }) { _ ->
            MockResponse(200, mapOf("Location" to "http://127.0.0.1:$serverPort$sessionPath"), "")
        }

        val putCalls = AtomicInteger(0)
        val receivedRanges = CopyOnWriteArrayList<String>()

        server.on({ method, path -> method == "PUT" && path == sessionPath }) { req ->
            val call = putCalls.incrementAndGet()
            val contentRange = req.headers["content-range"] ?: ""
            receivedRanges.add(contentRange)

            when (call) {
                1 -> {
                    // First chunk sent: returns 308 WITHOUT Range header
                    MockResponse(308, emptyMap())
                }
                2 -> {
                    // Status query sent: returns 308 without Range header as well
                    MockResponse(308, emptyMap())
                }
                3 -> {
                    // Next chunk sent: client must NOT have skipped bytes; it MUST re-send chunk 0
                    assertEquals("bytes 0-1048575/$totalBytes", contentRange)
                    MockResponse(308, mapOf("Range" to "bytes=0-1048575"))
                }
                4 -> {
                    // Second chunk completes
                    assertEquals("bytes 1048576-2097151/$totalBytes", contentRange)
                    val response = "{\"id\":\"file_no_range_safe\",\"name\":\"cayana-v1-norange.cynb\",\"size\":\"$totalBytes\",\"createdTime\":\"2026-10-07T00:00:00Z\"}"
                    MockResponse(200, emptyMap(), response)
                }
                else -> MockResponse(500)
            }
        }

        val client = createClient(chunkSize = chunkSize)
        val result = client.uploadBackup("cayana-v1-norange.cynb", data)

        assertTrue(result is Result.Success)
        assertEquals("file_no_range_safe", (result as Result.Success).data.fileId)
        // Verify call sequence: chunk 0 -> query -> chunk 0 re-sent (no bytes skipped!)
        assertEquals("bytes 0-1048575/$totalBytes", receivedRanges[0])
        assertEquals("bytes */$totalBytes", receivedRanges[1])
        assertEquals("bytes 0-1048575/$totalBytes", receivedRanges[2])
    }
}
