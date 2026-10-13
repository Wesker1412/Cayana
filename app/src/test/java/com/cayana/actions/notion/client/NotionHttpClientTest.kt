package com.cayana.actions.notion.client

import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.backup.drive.MockHttpServer
import com.cayana.backup.drive.MockResponse
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotionHttpClientTest {

    private lateinit var server: MockHttpServer
    private var baseUrl: String = ""
    private val logger = TestLogger()

    @Before
    fun setUp() {
        server = MockHttpServer()
        baseUrl = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun searchTargetsSendsCorrectHeadersAndParsesAllTargetTypes() = runTest {
        var recordedAuth: String? = null
        var recordedVersion: String? = null
        var recordedBody: String? = null

        server.on({ method, path -> method == "POST" && path == "/search" }) { req ->
            recordedAuth = req.headers["authorization"]
            recordedVersion = req.headers["notion-version"]
            recordedBody = String(req.body, Charsets.UTF_8)
            MockResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                bodyString = """
                    {
                        "object": "list",
                        "results": [
                            {
                                "object": "page",
                                "id": "page-123",
                                "url": "https://notion.so/page-123",
                                "properties": {
                                    "Title": {
                                        "type": "title",
                                        "title": [
                                            { "plain_text": "My Project Page" }
                                        ]
                                    }
                                }
                            },
                            {
                                "object": "database",
                                "id": "db-456",
                                "url": "https://notion.so/db-456",
                                "title": [
                                    { "plain_text": "Engineering Roadmap" }
                                ]
                            },
                            {
                                "object": "data_source",
                                "id": "ds-789",
                                "url": "https://notion.so/ds-789",
                                "title": [
                                    { "plain_text": "Primary Tasks Table" }
                                ]
                            }
                        ],
                        "next_cursor": "cursor-abc-123",
                        "has_more": true
                    }
                """.trimIndent()
            )
        }

        val client = NotionHttpClient(
            apiBaseUrl = baseUrl,
            notionVersion = "2026-03-11",
            logger = logger
        )

        val result = client.searchTargets(
            accessToken = "secret_notion_token_xyz",
            query = "Project",
            startCursor = "cursor-start",
            pageSize = 25
        )

        assertEquals("Bearer secret_notion_token_xyz", recordedAuth)
        assertEquals("2026-03-11", recordedVersion)

        val requestJson = JSONObject(recordedBody!!)
        assertEquals("Project", requestJson.getString("query"))
        assertEquals("cursor-start", requestJson.getString("start_cursor"))
        assertEquals(25, requestJson.getInt("page_size"))

        assertTrue(result is Result.Success)
        val response = (result as Result.Success).data
        assertEquals("cursor-abc-123", response.nextCursor)
        assertTrue(response.hasMore)
        assertEquals(3, response.targets.size)

        val pageTarget = response.targets[0]
        assertEquals("page-123", pageTarget.id)
        assertEquals("My Project Page", pageTarget.title)
        assertEquals(NotionTargetType.PAGE, pageTarget.type)
        assertTrue(pageTarget.isSelectable)

        val dbTarget = response.targets[1]
        assertEquals("db-456", dbTarget.id)
        assertEquals("Engineering Roadmap", dbTarget.title)
        assertEquals(NotionTargetType.DATABASE, dbTarget.type)
        assertFalse(dbTarget.isSelectable)

        val dsTarget = response.targets[2]
        assertEquals("ds-789", dsTarget.id)
        assertEquals("Primary Tasks Table", dsTarget.title)
        assertEquals(NotionTargetType.DATA_SOURCE, dsTarget.type)
        assertTrue(dsTarget.isSelectable)
    }

    @Test
    fun searchTargetsHandles401UnauthorizedAsNotionAuthRequiredException() = runTest {
        server.on({ method, path -> method == "POST" && path == "/search" }) {
            MockResponse(statusCode = 401, bodyString = """{"code": "unauthorized"}""")
        }

        val client = NotionHttpClient(
            apiBaseUrl = baseUrl,
            notionVersion = "2026-03-11",
            logger = logger
        )

        val result = client.searchTargets(accessToken = "expired_token", query = null)

        assertTrue(result is Result.Error)
        val ex = (result as Result.Error).exception
        assertTrue(ex is NotionAuthRequiredException)
    }

    @Test
    fun searchTargetsHandles403ForbiddenAsSecurityException() = runTest {
        server.on({ method, path -> method == "POST" && path == "/search" }) {
            MockResponse(statusCode = 403, bodyString = """{"code": "restricted_resource"}""")
        }

        val client = NotionHttpClient(
            apiBaseUrl = baseUrl,
            notionVersion = "2026-03-11",
            logger = logger
        )

        val result = client.searchTargets(accessToken = "token", query = null)

        assertTrue(result is Result.Error)
        val ex = (result as Result.Error).exception
        assertTrue(ex is SecurityException)
    }

    @Test
    fun createPageBuildsCorrectParentAndBlocksForDataSource() = runTest {
        var recordedBody: String? = null
        server.on({ method, path -> method == "GET" && path.startsWith("/data_sources/ds-999") }) {
            MockResponse(
                statusCode = 200,
                bodyString = """{"properties": {"Title": {"type": "title"}}}"""
            )
        }
        server.on({ method, path -> method == "POST" && path == "/pages" }) { req ->
            recordedBody = String(req.body, Charsets.UTF_8)
            MockResponse(
                statusCode = 200,
                bodyString = """{"object": "page", "id": "new-page-id", "url": "https://notion.so/new-page-id"}"""
            )
        }

        val client = NotionHttpClient(
            apiBaseUrl = baseUrl,
            notionVersion = "2026-03-11",
            logger = logger
        )

        val result = client.createPage(
            accessToken = "token_abc",
            targetId = "ds-999",
            targetType = NotionTargetType.DATA_SOURCE,
            title = "My Captured Idea",
            content = "Important notes here",
            sourceMemoryId = "mem-101"
        )

        assertTrue(result is Result.Success)
        val created = (result as Result.Success).data
        assertEquals("new-page-id", created.id)
        assertEquals("https://notion.so/new-page-id", created.url)
        assertEquals("My Captured Idea", created.title)

        val requestJson = JSONObject(recordedBody!!)
        val parent = requestJson.getJSONObject("parent")
        assertEquals("ds-999", parent.getString("data_source_id"))
        assertFalse(parent.has("database_id"))
        assertFalse(parent.has("page_id"))

        val children = requestJson.getJSONArray("children")
        assertEquals(2, children.length())
        val bodyBlock = children.getJSONObject(0).getJSONObject("paragraph")
        assertEquals("Important notes here", bodyBlock.getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))

        val memoryBlock = children.getJSONObject(1).getJSONObject("paragraph")
        assertEquals("Source: Cayana Memory mem-101", memoryBlock.getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
    }

    @Test
    fun createPageRejectsDatabaseDirectWritingAndAcceptsPage() = runTest {
        var recordedBody: String? = null
        server.on({ method, path -> method == "POST" && path == "/pages" }) { req ->
            recordedBody = String(req.body, Charsets.UTF_8)
            MockResponse(statusCode = 200, bodyString = """{"object": "page", "id": "p-1"}""")
        }

        val client = NotionHttpClient(
            apiBaseUrl = baseUrl,
            notionVersion = "2026-03-11",
            logger = logger
        )

        // 1. Direct creation under DATABASE must fail
        val dbResult = client.createPage(
            accessToken = "token_abc",
            targetId = "db-888",
            targetType = NotionTargetType.DATABASE,
            title = "Title DB"
        )
        assertTrue(dbResult is Result.Error)
        assertTrue((dbResult as Result.Error).exception is IllegalArgumentException)

        // 2. Creation under PAGE must succeed with page_id
        val pageResult = client.createPage(
            accessToken = "token_abc",
            targetId = "page-777",
            targetType = NotionTargetType.PAGE,
            title = "Title Page"
        )
        assertTrue(pageResult is Result.Success)
        val parent = JSONObject(recordedBody!!).getJSONObject("parent")
        assertEquals("page-777", parent.getString("page_id"))
    }

    @Test
    fun getDataSourceTitlePropertyReturnsErrorWhenNoTitlePropertyOrHttpError() = runTest {
        server.on({ method, path -> method == "GET" && path == "/data_sources/ds-no-title" }) {
            MockResponse(
                statusCode = 200,
                bodyString = """{"properties": {"Description": {"type": "rich_text"}}}"""
            )
        }
        server.on({ method, path -> method == "GET" && path == "/data_sources/ds-error" }) {
            MockResponse(statusCode = 500, bodyString = """{"code": "internal_error"}""")
        }

        val client = NotionHttpClient(
            apiBaseUrl = baseUrl,
            notionVersion = "2026-03-11",
            logger = logger
        )

        val noTitleRes = client.getDataSourceTitleProperty("token", "ds-no-title")
        assertTrue(noTitleRes is Result.Error)

        val errRes = client.getDataSourceTitleProperty("token", "ds-error")
        assertTrue(errRes is Result.Error)
    }

    @Test
    fun verifyPageReferenceValidatesParentAndChildBlocks() = runTest {
        server.on({ method, path -> method == "GET" && path == "/pages/page-valid" }) {
            MockResponse(
                statusCode = 200,
                bodyString = """{"parent": {"page_id": "target-123"}}"""
            )
        }
        server.on({ method, path -> method == "GET" && path.startsWith("/blocks/page-valid/children") }) {
            MockResponse(
                statusCode = 200,
                bodyString = """
                    {
                        "results": [
                            {
                                "object": "block",
                                "type": "paragraph",
                                "paragraph": {
                                    "rich_text": [
                                        {"type": "text", "text": {"content": "Source: Cayana Memory mem-xyz"}}
                                    ]
                                }
                            }
                        ]
                    }
                """.trimIndent()
            )
        }

        server.on({ method, path -> method == "GET" && path == "/pages/page-mismatch-parent" }) {
            MockResponse(
                statusCode = 200,
                bodyString = """{"parent": {"page_id": "other-target"}}"""
            )
        }

        val client = NotionHttpClient(
            apiBaseUrl = baseUrl,
            notionVersion = "2026-03-11",
            logger = logger
        )

        // Matching parent and memory reference -> Success(true)
        val validRes = client.verifyPageReference("token", "page-valid", "target-123", "mem-xyz")
        assertTrue(validRes is Result.Success)
        assertTrue((validRes as Result.Success).data)

        // Mismatched memory reference -> Success(false)
        val wrongMemRes = client.verifyPageReference("token", "page-valid", "target-123", "wrong-mem")
        assertTrue(wrongMemRes is Result.Success)
        assertFalse((wrongMemRes as Result.Success).data)

        // Mismatched parent -> Success(false)
        val mismatchParentRes = client.verifyPageReference("token", "page-mismatch-parent", "target-123", "mem-xyz")
        assertTrue(mismatchParentRes is Result.Success)
        assertFalse((mismatchParentRes as Result.Success).data)
    }

    private class TestLogger : CayanaLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?) {}
    }
}
