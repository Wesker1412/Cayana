package com.cayana.actions.tasks

import com.cayana.actions.tasks.client.GoogleTasksHttpClient
import com.cayana.actions.tasks.client.TasksHttpException
import com.cayana.backup.drive.MockHttpServer
import com.cayana.backup.drive.MockResponse
import com.cayana.core.common.Result
import com.cayana.core.logging.DefaultCayanaLogger
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleTasksPaginationTest {

    private lateinit var server: MockHttpServer
    private var port: Int = 0
    private lateinit var client: GoogleTasksHttpClient
    private val requestedPaths = CopyOnWriteArrayList<String>()

    @Before
    fun setup() {
        server = MockHttpServer()
        port = server.port

        client = GoogleTasksHttpClient(
            baseUrl = "http://127.0.0.1:$port",
            connectTimeoutMs = 1000,
            readTimeoutMs = 2000,
            logger = DefaultCayanaLogger()
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun findTaskByMemoryRefPagesThroughNextPageTokenUntilFound() = runTest {
        server.on({ _, _ -> true }) { req ->
            requestedPaths.add(req.path)
            if (!req.path.contains("pageToken=")) {
                // Page 1: returns items without match, and has nextPageToken
                val page1Items = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "task-p1-1")
                        put("title", "無關待辦 1")
                        put("notes", "一些備忘")
                    })
                }
                val resp = JSONObject().apply {
                    put("items", page1Items)
                    put("nextPageToken", "token-page-2")
                }.toString()
                MockResponse(statusCode = 200, bodyString = resp)
            } else if (req.path.contains("pageToken=token-page-2")) {
                // Page 2: returns matching item
                val page2Items = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "task-p2-target")
                        put("title", "目標待辦")
                        put("notes", "cayana://memory/mem-target-999")
                    })
                }
                val resp = JSONObject().apply {
                    put("items", page2Items)
                }.toString()
                MockResponse(statusCode = 200, bodyString = resp)
            } else {
                MockResponse(statusCode = 404, bodyString = "Not Found")
            }
        }

        val result = client.findTaskByMemoryRef(
            accessToken = "mock-token",
            taskListId = "list-1",
            sourceMemoryId = "mem-target-999",
            title = "目標待辦"
        )

        assertTrue("Pagination should succeed", result is Result.Success)
        val found = (result as Result.Success).data
        assertNotNull(found)
        assertEquals("task-p2-target", found?.id)
        assertEquals(2, requestedPaths.size)
        assertTrue(requestedPaths[0].contains("maxResults=100"))
        assertTrue(requestedPaths[1].contains("pageToken=token-page-2"))
    }

    @Test
    fun findTaskByMemoryRefReturnsNullWhenAllPagesExhaustedWithoutMatch() = runTest {
        server.on({ _, _ -> true }) { req ->
            requestedPaths.add(req.path)
            if (!req.path.contains("pageToken=")) {
                val resp = JSONObject().apply {
                    put("items", JSONArray())
                    put("nextPageToken", "token-2")
                }.toString()
                MockResponse(statusCode = 200, bodyString = resp)
            } else {
                val resp = JSONObject().apply {
                    put("items", JSONArray())
                }.toString()
                MockResponse(statusCode = 200, bodyString = resp)
            }
        }

        val result = client.findTaskByMemoryRef(
            accessToken = "mock-token",
            taskListId = "list-1",
            sourceMemoryId = "mem-nonexistent",
            title = "不存在"
        )

        assertTrue(result is Result.Success)
        assertNull((result as Result.Success).data)
        assertEquals(2, requestedPaths.size)
    }

    @Test
    fun findTaskByMemoryRefFailsClosedOnErrorDuringPagination() = runTest {
        server.on({ _, _ -> true }) { req ->
            requestedPaths.add(req.path)
            if (!req.path.contains("pageToken=")) {
                val resp = JSONObject().apply {
                    put("items", JSONArray())
                    put("nextPageToken", "token-2")
                }.toString()
                MockResponse(statusCode = 200, bodyString = resp)
            } else {
                // Page 2 fails with 500
                MockResponse(statusCode = 500, bodyString = "{\"error\": \"backend_error\"}")
            }
        }

        val result = client.findTaskByMemoryRef(
            accessToken = "mock-token",
            taskListId = "list-1",
            sourceMemoryId = "mem-unconfirmed",
            title = "未確認"
        )

        assertTrue("Failure during pagination must return Result.Error to prevent duplicate POSTs", result is Result.Error)
        val ex = (result as Result.Error).exception
        assertTrue(ex is TasksHttpException.ServerError)
    }
}
