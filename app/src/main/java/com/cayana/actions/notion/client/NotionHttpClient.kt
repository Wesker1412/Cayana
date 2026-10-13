package com.cayana.actions.notion.client

import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.actions.notion.auth.NotionRateLimitedException
import com.cayana.actions.notion.auth.NotionServerException
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class NotionHttpClient(
    private val apiBaseUrl: String = "https://api.notion.com/v1",
    private val notionVersion: String = "2026-03-11",
    private val logger: CayanaLogger
) : NotionClient {

    companion object {
        private const val TAG = "NotionHttpClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val dataSourceTitleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun searchTargets(
        accessToken: String,
        query: String?,
        startCursor: String?,
        pageSize: Int
    ): Result<NotionSearchResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${apiBaseUrl.trimEnd('/')}/search")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Notion-Version", notionVersion)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject().apply {
                put("page_size", pageSize.coerceIn(1, 100))
                if (!query.isNullOrBlank()) {
                    put("query", query.trim())
                }
                if (!startCursor.isNullOrBlank()) {
                    put("start_cursor", startCursor.trim())
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }

            val status = conn.responseCode
            if (status == 401) {
                logger.w(TAG, "Notion search returned 401 Unauthorized")
                return@withContext Result.Error(NotionAuthRequiredException("Notion API 401 Unauthorized"))
            }
            if (status == 403) {
                logger.w(TAG, "Notion search returned 403 Forbidden")
                return@withContext Result.Error(SecurityException("Notion 權限不足，請確認整合授權已包含目標頁面"))
            }

            if (status in 200..299) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val resultsArray = json.optJSONArray("results") ?: JSONArray()
                val nextCursor = json.optString("next_cursor").takeIf { it.isNotBlank() }
                val hasMore = json.optBoolean("has_more", false)
                val targets = mutableListOf<NotionTarget>()

                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.optJSONObject(i) ?: continue
                    val objType = item.optString("object")
                    val id = item.optString("id")
                    if (id.isBlank()) continue

                    val targetType = when (objType) {
                        "data_source" -> NotionTargetType.DATA_SOURCE
                        "database" -> NotionTargetType.DATABASE
                        "page" -> NotionTargetType.PAGE
                        else -> continue
                    }

                    val title = parseTargetTitle(item, targetType)
                    val urlStr = item.optString("url").takeIf { it.isNotBlank() }
                    val icon = parseIcon(item.optJSONObject("icon"))

                    // DATA_SOURCE and PAGE can directly accept new pages in Notion 2026-03-11.
                    // DATABASE containers are marked unselectable to prompt selecting their underlying data source.
                    val isSelectable = targetType == NotionTargetType.DATA_SOURCE ||
                            targetType == NotionTargetType.PAGE

                    val parentObj = item.optJSONObject("parent")
                    val parentId = parentObj?.optString("data_source_id")?.takeIf { it.isNotBlank() }
                        ?: parentObj?.optString("page_id")?.takeIf { it.isNotBlank() }
                        ?: parentObj?.optString("database_id")?.takeIf { it.isNotBlank() }

                    targets.add(
                        NotionTarget(
                            id = id,
                            title = title,
                            type = targetType,
                            icon = icon,
                            url = urlStr,
                            isSelectable = isSelectable,
                            parentId = parentId
                        )
                    )
                }

                logger.i(TAG, "Successfully searched Notion targets, found ${targets.size} items (hasMore=$hasMore)")
                Result.Success(NotionSearchResponse(targets = targets, nextCursor = nextCursor, hasMore = hasMore))
            } else {
                logger.w(TAG, "searchTargets failed with status $status")
                Result.Error(IOException("Failed searching Notion targets: HTTP $status"))
            }
        } catch (e: Exception) {
            logger.w(TAG, "searchTargets exception: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    override suspend fun createPage(
        accessToken: String,
        targetId: String,
        targetType: NotionTargetType,
        title: String,
        content: String?,
        sourceMemoryId: String?
    ): Result<NotionCreatedPage> = withContext(Dispatchers.IO) {
        if (targetType == NotionTargetType.DATABASE) {
            logger.w(TAG, "createPage rejected: Database direct page creation is unsupported in Notion 2026-03-11")
            return@withContext Result.Error(
                IllegalArgumentException("Database direct page creation is unsupported in Notion 2026-03-11. Please select a Data Source or Page.")
            )
        }
        try {
            val url = URL("${apiBaseUrl.trimEnd('/')}/pages")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Notion-Version", notionVersion)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject()

            // Parent specification in Notion 2026-03-11:
            // DATA_SOURCE -> data_source_id
            // PAGE -> page_id
            val parent = JSONObject()
            when (targetType) {
                NotionTargetType.DATA_SOURCE -> parent.put("data_source_id", targetId)
                NotionTargetType.PAGE -> parent.put("page_id", targetId)
                NotionTargetType.DATABASE -> parent.put("database_id", targetId)
            }
            body.put("parent", parent)

            // Properties: dynamic title property for DATA_SOURCE, "title" for PAGE
            val titlePropName = if (targetType == NotionTargetType.DATA_SOURCE) {
                when (val propRes = getDataSourceTitleProperty(accessToken, targetId)) {
                    is Result.Success -> propRes.data
                    is Result.Error -> {
                        logger.w(TAG, "Schema resolution failed for data source: ${propRes.exception.javaClass.simpleName}")
                        return@withContext Result.Error(propRes.exception)
                    }
                    Result.Loading -> {
                        return@withContext Result.Error(IllegalStateException("Schema resolution loading"))
                    }
                }
            } else {
                "title"
            }

            val titleProp = JSONObject().apply {
                val titleArray = JSONArray().apply {
                    val textObj = JSONObject().apply {
                        put("type", "text")
                        put("text", JSONObject().put("content", title))
                    }
                    put(textObj)
                }
                put("title", titleArray)
            }
            val properties = JSONObject().apply {
                put(titlePropName, titleProp)
            }
            body.put("properties", properties)

            // Children blocks: limit content to 2000 characters
            val cleanContent = content?.take(2000)?.takeIf { it.isNotBlank() }
            val childrenArray = JSONArray()
            if (!cleanContent.isNullOrBlank()) {
                val contentBlock = JSONObject().apply {
                    put("object", "block")
                    put("type", "paragraph")
                    put(
                        "paragraph",
                        JSONObject().apply {
                            val richTextArray = JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", JSONObject().put("content", cleanContent))
                                    }
                                )
                            }
                            put("rich_text", richTextArray)
                        }
                    )
                }
                childrenArray.put(contentBlock)
            }

            if (!sourceMemoryId.isNullOrBlank()) {
                val memoryBlock = JSONObject().apply {
                    put("object", "block")
                    put("type", "paragraph")
                    put(
                        "paragraph",
                        JSONObject().apply {
                            val richTextArray = JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", JSONObject().put("content", "Source: Cayana Memory $sourceMemoryId"))
                                    }
                                )
                            }
                            put("rich_text", richTextArray)
                        }
                    )
                }
                childrenArray.put(memoryBlock)
            }

            if (childrenArray.length() > 0) {
                body.put("children", childrenArray)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }

            val status = conn.responseCode
            if (status == 401) {
                logger.w(TAG, "Notion createPage returned 401 Unauthorized")
                return@withContext Result.Error(NotionAuthRequiredException("Notion API 401 Unauthorized"))
            }
            if (status == 403) {
                logger.w(TAG, "Notion createPage returned 403 Forbidden")
                return@withContext Result.Error(SecurityException("Notion 權限不足"))
            }
            if (status == 429) {
                logger.w(TAG, "Notion createPage returned 429 Rate Limited")
                return@withContext Result.Error(NotionRateLimitedException("Notion API 429 Rate Limited"))
            }
            if (status in 500..599) {
                logger.w(TAG, "Notion createPage returned 5xx Server Error: $status")
                return@withContext Result.Error(NotionServerException(status, "Notion API $status Server Error"))
            }

            if (status in 200..299) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val pageId = json.optString("id")
                val pageUrl = json.optString("url").takeIf { it.isNotBlank() }

                logger.i(TAG, "Successfully created Notion page in target $targetId")
                Result.Success(NotionCreatedPage(id = pageId, url = pageUrl, title = title))
            } else {
                logger.w(TAG, "createPage failed with status $status")
                Result.Error(IOException("Failed creating Notion page: HTTP $status"))
            }
        } catch (e: Exception) {
            logger.w(TAG, "createPage exception: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    private fun parseTargetTitle(item: JSONObject, targetType: NotionTargetType): String {
        return try {
            when (targetType) {
                NotionTargetType.DATA_SOURCE -> {
                    val titleArray = item.optJSONArray("title")
                    if (titleArray != null && titleArray.length() > 0) {
                        titleArray.getJSONObject(0).optString("plain_text", "Untitled Data Source")
                    } else {
                        item.optString("name").takeIf { it.isNotBlank() } ?: "Untitled Data Source"
                    }
                }
                NotionTargetType.DATABASE -> {
                    val titleArray = item.optJSONArray("title") ?: JSONArray()
                    if (titleArray.length() > 0) {
                        titleArray.getJSONObject(0).optString("plain_text", "Untitled Database")
                    } else {
                        "Untitled Database"
                    }
                }
                NotionTargetType.PAGE -> {
                    val props = item.optJSONObject("properties") ?: return "Untitled Page"
                    for (key in props.keys()) {
                        val prop = props.optJSONObject(key) ?: continue
                        if (prop.optString("type") == "title") {
                            val titleArray = prop.optJSONArray("title") ?: continue
                            if (titleArray.length() > 0) {
                                return titleArray.getJSONObject(0).optString("plain_text", "Untitled Page")
                            }
                        }
                    }
                    "Untitled Page"
                }
            }
        } catch (_: Exception) {
            "Untitled"
        }
    }

    private fun parseIcon(iconObj: JSONObject?): String? {
        if (iconObj == null) return null
        return when (iconObj.optString("type")) {
            "emoji" -> iconObj.optString("emoji")
            "external" -> iconObj.optJSONObject("external")?.optString("url")
            "file" -> iconObj.optJSONObject("file")?.optString("url")
            else -> null
        }
    }

    private fun extractErrorCode(errJson: String): String? {
        return try {
            val json = JSONObject(errJson)
            json.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
                ?: json.optString("code").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getDataSourceTitleProperty(
        accessToken: String,
        dataSourceId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        dataSourceTitleCache[dataSourceId]?.let { return@withContext Result.Success(it) }
        try {
            val url = URL("${apiBaseUrl.trimEnd('/')}/data_sources/$dataSourceId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Notion-Version", notionVersion)
                setRequestProperty("Accept", "application/json")
            }

            val status = conn.responseCode
            if (status == 401) {
                return@withContext Result.Error(NotionAuthRequiredException("Notion API 401 Unauthorized"))
            }
            if (status == 403) {
                return@withContext Result.Error(SecurityException("Notion 權限不足"))
            }

            if (status in 200..299) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val properties = json.optJSONObject("properties")
                if (properties != null) {
                    val keys = properties.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val prop = properties.optJSONObject(key)
                        if (prop?.optString("type") == "title") {
                            dataSourceTitleCache[dataSourceId] = key
                            return@withContext Result.Success(key)
                        }
                    }
                }
                logger.w(TAG, "No title property found in data source schema")
                Result.Error(IllegalStateException("No title property found in data source schema"))
            } else {
                logger.w(TAG, "getDataSourceTitleProperty failed: status $status")
                Result.Error(IOException("Failed querying data source schema: HTTP $status"))
            }
        } catch (e: Exception) {
            logger.w(TAG, "getDataSourceTitleProperty exception: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    override suspend fun verifyPageReference(
        accessToken: String,
        pageId: String,
        expectedTargetId: String,
        expectedSourceMemoryId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Verify parent via GET /v1/pages/$pageId
            val pageUrl = URL("${apiBaseUrl.trimEnd('/')}/pages/$pageId")
            val pageConn = (pageUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Notion-Version", notionVersion)
                setRequestProperty("Accept", "application/json")
            }

            val pageStatus = pageConn.responseCode
            if (pageStatus == 401) {
                return@withContext Result.Error(NotionAuthRequiredException("Notion API 401 Unauthorized"))
            }
            if (pageStatus == 403) {
                return@withContext Result.Error(SecurityException("Notion 權限不足"))
            }
            if (pageStatus == 404) {
                return@withContext Result.Success(false)
            }
            if (pageStatus !in 200..299) {
                return@withContext Result.Error(IOException("Failed reading page: HTTP $pageStatus"))
            }

            val pageResponse = readStream(pageConn.inputStream)
            val pageJson = JSONObject(pageResponse)
            val parentObj = pageJson.optJSONObject("parent")
            val actualParentId = parentObj?.optString("data_source_id")?.takeIf { it.isNotBlank() }
                ?: parentObj?.optString("page_id")?.takeIf { it.isNotBlank() }
                ?: parentObj?.optString("database_id")?.takeIf { it.isNotBlank() }

            if (actualParentId != expectedTargetId) {
                logger.d(TAG, "verifyPageReference: parent mismatch ($actualParentId != $expectedTargetId)")
                return@withContext Result.Success(false)
            }

            // 2. Verify Cayana Reference block via GET /v1/blocks/$pageId/children
            val blocksUrl = URL("${apiBaseUrl.trimEnd('/')}/blocks/$pageId/children?page_size=50")
            val blocksConn = (blocksUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Notion-Version", notionVersion)
                setRequestProperty("Accept", "application/json")
            }

            val blocksStatus = blocksConn.responseCode
            if (blocksStatus !in 200..299) {
                return@withContext Result.Error(IOException("Failed reading blocks: HTTP $blocksStatus"))
            }

            val blocksResponse = readStream(blocksConn.inputStream)
            val blocksJson = JSONObject(blocksResponse)
            val results = blocksJson.optJSONArray("results") ?: JSONArray()
            val targetReference = "Source: Cayana Memory $expectedSourceMemoryId"

            for (i in 0 until results.length()) {
                val block = results.optJSONObject(i) ?: continue
                val paragraph = block.optJSONObject("paragraph") ?: continue
                val richText = paragraph.optJSONArray("rich_text") ?: continue
                for (j in 0 until richText.length()) {
                    val rt = richText.optJSONObject(j) ?: continue
                    val content = rt.optJSONObject("text")?.optString("content") ?: rt.optString("plain_text")
                    if (content.contains(targetReference)) {
                        return@withContext Result.Success(true)
                    }
                }
            }

            Result.Success(false)
        } catch (e: Exception) {
            logger.w(TAG, "verifyPageReference exception: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}

