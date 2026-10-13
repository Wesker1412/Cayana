package com.cayana.actions.notion.auth

import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class NotionOAuthInitiateResponse(
    val authorizeUrl: String,
    val state: String
)

data class NotionOAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val workspaceId: String?,
    val workspaceName: String?,
    val workspaceIcon: String?,
    val botId: String?
)

interface NotionOAuthBrokerClient {
    suspend fun initiateOAuth(redirectUri: String? = null): Result<NotionOAuthInitiateResponse>
    suspend fun exchangeCode(code: String, state: String, redirectUri: String? = null): Result<NotionOAuthTokenResponse>
    suspend fun refreshToken(refreshToken: String): Result<NotionOAuthTokenResponse>
}

class HttpNotionOAuthBrokerClient(
    private val brokerBaseUrl: String = "https://api.cayana.com",
    private val logger: CayanaLogger
) : NotionOAuthBrokerClient {

    companion object {
        private const val TAG = "NotionBrokerClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    override suspend fun initiateOAuth(redirectUri: String?): Result<NotionOAuthInitiateResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${brokerBaseUrl.trimEnd('/')}/v1/oauth/notion/initiate")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject()
            if (!redirectUri.isNullOrBlank()) {
                body.put("redirect_uri", redirectUri)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }

            val status = conn.responseCode
            if (status in 200..299) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val authUrl = json.getString("authorize_url")
                val state = json.getString("state")
                Result.Success(NotionOAuthInitiateResponse(authorizeUrl = authUrl, state = state))
            } else {
                val errText = readStream(conn.errorStream)
                logger.w(TAG, "initiateOAuth failed with HTTP $status: $errText")
                Result.Error(RuntimeException("Failed initiating Notion OAuth: HTTP $status"))
            }
        } catch (e: Exception) {
            logger.w(TAG, "initiateOAuth exception: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    override suspend fun exchangeCode(
        code: String,
        state: String,
        redirectUri: String?
    ): Result<NotionOAuthTokenResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${brokerBaseUrl.trimEnd('/')}/v1/oauth/notion/exchange")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject().apply {
                put("code", code)
                put("state", state)
                if (!redirectUri.isNullOrBlank()) {
                    put("redirect_uri", redirectUri)
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }

            val status = conn.responseCode
            if (status in 200..299) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val workspaceId = json.optString("workspace_id").takeIf { it.isNotBlank() }
                val workspaceName = json.optString("workspace_name").takeIf { it.isNotBlank() }
                val workspaceIcon = json.optString("workspace_icon").takeIf { it.isNotBlank() }
                val botId = json.optString("bot_id").takeIf { it.isNotBlank() }

                // Privacy guarantee: strictly avoid logging access_token or refresh_token!
                logger.i(TAG, "Successfully exchanged Notion OAuth code for workspace $workspaceId")
                Result.Success(
                    NotionOAuthTokenResponse(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        workspaceId = workspaceId,
                        workspaceName = workspaceName,
                        workspaceIcon = workspaceIcon,
                        botId = botId
                    )
                )
            } else {
                val errText = readStream(conn.errorStream)
                logger.w(TAG, "exchangeCode failed with HTTP $status: $errText")
                Result.Error(RuntimeException("Failed exchanging Notion OAuth code: HTTP $status"))
            }
        } catch (e: Exception) {
            logger.w(TAG, "exchangeCode exception: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    override suspend fun refreshToken(refreshToken: String): Result<NotionOAuthTokenResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${brokerBaseUrl.trimEnd('/')}/v1/oauth/notion/refresh")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject().apply {
                put("refresh_token", refreshToken)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }

            val status = conn.responseCode
            if (status in 200..299) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val newAccessToken = json.getString("access_token")
                val newRefreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
                val workspaceId = json.optString("workspace_id").takeIf { it.isNotBlank() }
                val workspaceName = json.optString("workspace_name").takeIf { it.isNotBlank() }
                val workspaceIcon = json.optString("workspace_icon").takeIf { it.isNotBlank() }
                val botId = json.optString("bot_id").takeIf { it.isNotBlank() }

                logger.i(TAG, "Successfully refreshed Notion OAuth tokens")
                Result.Success(
                    NotionOAuthTokenResponse(
                        accessToken = newAccessToken,
                        refreshToken = newRefreshToken,
                        workspaceId = workspaceId,
                        workspaceName = workspaceName,
                        workspaceIcon = workspaceIcon,
                        botId = botId
                    )
                )
            } else {
                val errText = readStream(conn.errorStream)
                logger.w(TAG, "refreshToken failed with HTTP $status: $errText")
                Result.Error(RuntimeException("Failed refreshing Notion OAuth token: HTTP $status"))
            }
        } catch (e: Exception) {
            logger.w(TAG, "refreshToken exception: ${e.javaClass.simpleName}")
            Result.Error(e)
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}
