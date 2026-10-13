package com.cayana.actions.notion.client

import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
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

                    // DATA_SOURCE, PAGE, and DATABASE are valid identifiable targets
                    val isSelectable = targetType == NotionTargetType.DATA_SOURCE ||
                            targetType == NotionTargetType.PAGE ||
                            targetType == NotionTargetType.DATABASE

                    targets.add(
                        NotionTarget(
                            id = id,
                            title = title,
                            type = targetType,
                            icon = icon,
                            url = urlStr,
                            isSelectable = isSelectable
                        )
                    )
                }

                logger.i(TAG, "Successfully searched Notion targets, found ${targets.size} items (hasMore=$hasMore)")
                Result.Success(NotionSearchResponse(targets = targets, nextCursor = nextCursor, hasMore = hasMore))
            } else {
                val errText = readStream(conn.errorStream)
                val errCode = extractErrorCode(errText) ?: "HTTP_$status"
                logger.w(TAG, "searchTargets failed with status $status, errorCode=$errCode")
                Result.Error(RuntimeException("Failed searching Notion targets: $errCode"))
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
            // DATABASE -> database_id
            // PAGE -> page_id
            val parent = JSONObject()
            when (targetType) {
                NotionTargetType.DATA_SOURCE -> parent.put("data_source_id", targetId)
                NotionTargetType.DATABASE -> parent.put("database_id", targetId)
                NotionTargetType.PAGE -> parent.put("page_id", targetId)
            }
            body.put("parent", parent)

            // Properties (Title)
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
                put("title", titleProp)
            }
            body.put("properties", properties)

            // Children blocks
            val childrenArray = JSONArray()
            if (!content.isNullOrBlank()) {
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
                                        put("text", JSONObject().put("content", content))
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

            if (status in 200..299) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val pageId = json.optString("id")
                val pageUrl = json.optString("url").takeIf { it.isNotBlank() }

                logger.i(TAG, "Successfully created Notion page in target $targetId")
                Result.Success(NotionCreatedPage(id = pageId, url = pageUrl, title = title))
            } else {
                val errText = readStream(conn.errorStream)
                val errCode = extractErrorCode(errText) ?: "HTTP_$status"
                logger.w(TAG, "createPage failed with status $status, errorCode=$errCode")
                Result.Error(RuntimeException("Failed creating Notion page: $errCode"))
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

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}
