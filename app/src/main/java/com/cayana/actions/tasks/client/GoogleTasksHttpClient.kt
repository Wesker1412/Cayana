package com.cayana.actions.tasks.client

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
import java.nio.charset.StandardCharsets

class GoogleTasksHttpClient(
    private val baseUrl: String = "https://tasks.googleapis.com/tasks/v1",
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000,
    private val logger: CayanaLogger
) : GoogleTasksClient {

    companion object {
        private const val TAG = "GoogleTasksClient"

        private fun JSONObject.optNullableString(key: String): String? {
            return if (has(key) && !isNull(key)) optString(key) else null
        }
    }

    override suspend fun getTaskLists(accessToken: String): Result<List<GoogleTaskList>> = withContext(Dispatchers.IO) {
        val endpoint = "$baseUrl/users/@me/lists"
        try {
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseText = readStream(connection.inputStream)
                val json = JSONObject(responseText)
                val itemsArray = json.optJSONArray("items") ?: JSONArray()
                val list = mutableListOf<GoogleTaskList>()
                for (i in 0 until itemsArray.length()) {
                    val item = itemsArray.getJSONObject(i)
                    list.add(
                        GoogleTaskList(
                            id = item.getString("id"),
                            title = item.optString("title", ""),
                            updated = item.optNullableString("updated")
                        )
                    )
                }
                Result.Success(list)
            } else {
                val errorBody = readStream(connection.errorStream)
                val ex = parseHttpException(statusCode, errorBody)
                logger.w(TAG, "getTaskLists failed with status $statusCode")
                Result.Error(ex)
            }
        } catch (e: Exception) {
            logger.w(TAG, "Network failure during getTaskLists: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    override suspend fun createTask(
        accessToken: String,
        taskListId: String,
        title: String,
        notes: String?,
        dueRfc3339: String?
    ): Result<GoogleTaskItem> = withContext(Dispatchers.IO) {
        val endpoint = "$baseUrl/lists/$taskListId/tasks"
        try {
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            val requestJson = JSONObject().apply {
                put("title", title)
                if (!notes.isNullOrBlank()) {
                    put("notes", notes)
                }
                if (!dueRfc3339.isNullOrBlank()) {
                    put("due", dueRfc3339)
                }
            }

            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseText = readStream(connection.inputStream)
                val json = JSONObject(responseText)
                val item = GoogleTaskItem(
                    id = json.getString("id"),
                    title = json.optString("title", title),
                    notes = json.optNullableString("notes") ?: notes,
                    due = json.optNullableString("due") ?: dueRfc3339,
                    status = json.optString("status", "needsAction")
                )
                Result.Success(item)
            } else {
                val errorBody = readStream(connection.errorStream)
                val ex = parseHttpException(statusCode, errorBody)
                logger.w(TAG, "createTask failed with status $statusCode")
                Result.Error(ex)
            }
        } catch (e: Exception) {
            logger.w(TAG, "Network failure during createTask: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    override suspend fun findTaskByMemoryRef(
        accessToken: String,
        taskListId: String,
        sourceMemoryId: String,
        title: String
    ): Result<GoogleTaskItem?> = withContext(Dispatchers.IO) {
        var pageToken: String? = null
        var pagesChecked = 0
        val maxPages = 5
        val memoryRefPattern = "cayana://memory/$sourceMemoryId"

        do {
            pagesChecked++
            val endpoint = buildString {
                append("$baseUrl/lists/$taskListId/tasks?maxResults=100")
                if (!pageToken.isNullOrBlank()) {
                    append("&pageToken=").append(pageToken)
                }
            }

            val pageOutcome = try {
                val url = URL(endpoint)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Accept", "application/json")
                }

                val statusCode = connection.responseCode
                if (statusCode in 200..299) {
                    val responseText = readStream(connection.inputStream)
                    val json = JSONObject(responseText)
                    val itemsArray = json.optJSONArray("items") ?: JSONArray()
                    val nextPage = json.optNullableString("nextPageToken")

                    var foundItem: GoogleTaskItem? = null
                    for (i in 0 until itemsArray.length()) {
                        val itemJson = itemsArray.getJSONObject(i)
                        val itemNotes = itemJson.optString("notes", "")
                        val itemTitle = itemJson.optString("title", "")

                        if (itemNotes.contains(memoryRefPattern) || (itemTitle == title && itemNotes.contains(sourceMemoryId))) {
                            foundItem = GoogleTaskItem(
                                id = itemJson.getString("id"),
                                title = itemTitle,
                                notes = itemNotes,
                                due = itemJson.optNullableString("due"),
                                status = itemJson.optNullableString("status")
                            )
                            break
                        }
                    }
                    Pair(foundItem, nextPage)
                } else {
                    val ex = parseHttpException(statusCode, "HTTP $statusCode")
                    logger.w(TAG, "findTaskByMemoryRef page $pagesChecked failed with status $statusCode")
                    return@withContext Result.Error(ex)
                }
            } catch (e: Exception) {
                logger.w(TAG, "Network failure during findTaskByMemoryRef page $pagesChecked: ${e.javaClass.simpleName}")
                return@withContext Result.Error(e)
            }

            if (pageOutcome.first != null) {
                return@withContext Result.Success(pageOutcome.first)
            }
            pageToken = pageOutcome.second
        } while (!pageToken.isNullOrBlank() && pagesChecked < maxPages)

        Result.Success(null)
    }

    private fun readStream(inputStream: java.io.InputStream?): String {
        if (inputStream == null) return ""
        return BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    private fun parseHttpException(statusCode: Int, body: String): TasksHttpException {
        return when (statusCode) {
            401 -> TasksHttpException.Unauthorized(statusCode, "Unauthorized")
            429 -> TasksHttpException.RateLimited(statusCode, "Rate limited")
            in 500..599 -> TasksHttpException.ServerError(statusCode, "Server error: HTTP $statusCode")
            else -> TasksHttpException.ClientError(statusCode, "Client error: HTTP $statusCode")
        }
    }
}
