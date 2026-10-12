package com.cayana.actions.tasks.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GoogleTasksAuthorizationManager(
    private val context: Context,
    private val revokeUrlBase: String = "https://oauth2.googleapis.com/revoke",
    private val authClientProvider: () -> AuthorizationClient = { Identity.getAuthorizationClient(context) }
) : TasksAuthorizationManager {

    companion object {
        const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"

        fun buildAuthorizationRequest(): AuthorizationRequest {
            return AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(TASKS_SCOPE)))
                .build()
        }
    }

    private val _authStatus = MutableStateFlow(TasksAuthStatus.DISCONNECTED)
    override val authStatus: StateFlow<TasksAuthStatus> = _authStatus.asStateFlow()

    // Short-lived, memory-only access token. Strictly never written to disk or logged.
    @Volatile
    private var inMemoryAccessToken: String? = null

    private val authorizationClient: AuthorizationClient by lazy {
        authClientProvider()
    }

    override suspend fun getAccessToken(): Result<String> {
        val cached = inMemoryAccessToken
        if (!cached.isNullOrBlank()) {
            return Result.Success(cached)
        }

        return try {
            val request = buildAuthorizationRequest()
            val result = authorizationClient.authorize(request).await()

            if (result.hasResolution()) {
                _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
                Result.Error(TasksAuthRequiredException())
            } else {
                val token = result.accessToken
                val scopes = result.grantedScopes ?: emptyList()
                val hasTasksScope = scopes.any { it.equals(TASKS_SCOPE, ignoreCase = true) }

                if (!token.isNullOrBlank() && hasTasksScope) {
                    inMemoryAccessToken = token
                    _authStatus.value = TasksAuthStatus.CONNECTED
                    Result.Success(token)
                } else {
                    _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
                    Result.Error(TasksAuthRequiredException("取得的 Access Token 為空或未被授予 tasks 權限。"))
                }
            }
        } catch (e: Exception) {
            CayanaLogger.w("TasksAuth", "Failed to obtain Google Tasks access token: ${e.javaClass.simpleName}")
            _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
            Result.Error(e)
        }
    }

    override suspend fun getAuthorizationIntentSender(): Result<IntentSender?> {
        return try {
            val request = buildAuthorizationRequest()
            val result = authorizationClient.authorize(request).await()
            if (result.hasResolution()) {
                Result.Success(result.pendingIntent?.intentSender)
            } else {
                val token = result.accessToken
                val scopes = result.grantedScopes ?: emptyList()
                val hasTasksScope = scopes.any { it.equals(TASKS_SCOPE, ignoreCase = true) }
                if (!token.isNullOrBlank() && hasTasksScope) {
                    inMemoryAccessToken = token
                    _authStatus.value = TasksAuthStatus.CONNECTED
                    Result.Success(null)
                } else {
                    _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
                    Result.Error(TasksAuthRequiredException("取得的 Access Token 為空或未被授予 tasks 權限。"))
                }
            }
        } catch (e: Exception) {
            CayanaLogger.w("TasksAuth", "Failed getting intent sender: ${e.javaClass.simpleName}")
            _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
            Result.Error(e)
        }
    }

    override suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        if (data == null) {
            _authStatus.value = TasksAuthStatus.DISCONNECTED
            return@withContext Result.Error(TasksAuthRequiredException("授權結果資料為空。"))
        }

        try {
            val authResult = authorizationClient.getAuthorizationResultFromIntent(data)
            val token = authResult.accessToken
            val grantedScopes = authResult.grantedScopes ?: emptyList()
            val hasTasksScope = grantedScopes.any { it.equals(TASKS_SCOPE, ignoreCase = true) }

            if (token.isNullOrBlank()) {
                _authStatus.value = TasksAuthStatus.DISCONNECTED
                Result.Error(TasksAuthRequiredException("授權結果中的 Access Token 為空。"))
            } else if (!hasTasksScope) {
                _authStatus.value = TasksAuthStatus.DISCONNECTED
                Result.Error(TasksAuthRequiredException("授權結果缺少必要的 tasks 權限範圍。"))
            } else {
                inMemoryAccessToken = token
                _authStatus.value = TasksAuthStatus.CONNECTED
                Result.Success(Unit)
            }
        } catch (e: Exception) {
            CayanaLogger.w("TasksAuth", "解析授權 Intent 結果失敗: ${e.javaClass.simpleName}")
            _authStatus.value = TasksAuthStatus.DISCONNECTED
            Result.Error(e)
        }
    }

    override suspend fun invalidateCachedAccessToken() = withContext(Dispatchers.IO) {
        val oldToken = inMemoryAccessToken
        inMemoryAccessToken = null
        if (!oldToken.isNullOrBlank()) {
            try {
                GoogleAuthUtil.clearToken(context, oldToken)
            } catch (_: Exception) {}
        }
    }

    override suspend fun refreshAuthStatus(): TasksAuthStatus {
        if (!inMemoryAccessToken.isNullOrBlank()) {
            _authStatus.value = TasksAuthStatus.CONNECTED
            return TasksAuthStatus.CONNECTED
        }
        return when (getAccessToken()) {
            is Result.Success -> {
                _authStatus.value = TasksAuthStatus.CONNECTED
                TasksAuthStatus.CONNECTED
            }
            else -> {
                _authStatus.value
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        val tokenToRevoke = inMemoryAccessToken ?: run {
            when (val tokenResult = getAccessToken()) {
                is Result.Success -> tokenResult.data
                is Result.Error -> {
                    // Token cannot be obtained; clear local in-memory token and return Success
                    inMemoryAccessToken = null
                    _authStatus.value = TasksAuthStatus.DISCONNECTED
                    return@withContext Result.Success(Unit)
                }
                Result.Loading -> return@withContext Result.Error(IOException("Token loading"))
            }
        }
        // Immediately block new local callers before making the remote network call
        inMemoryAccessToken = null
        _authStatus.value = TasksAuthStatus.DISCONNECTED

        var revokeFailed = false
        var failureException: Exception? = null

        try {
            val url = URL(revokeUrlBase)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val postData = "token=" + java.net.URLEncoder.encode(tokenToRevoke, "UTF-8")
            conn.outputStream.use { os ->
                os.write(postData.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                os.flush()
            }
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                revokeFailed = true
                failureException = IOException("Google 權限撤銷失敗：HTTP $responseCode")
            }
        } catch (e: Exception) {
            CayanaLogger.w("TasksAuth", "撤銷 Google Tasks 授權失敗: ${e.javaClass.simpleName}")
            revokeFailed = true
            failureException = e
        } finally {
            try {
                GoogleAuthUtil.clearToken(context, tokenToRevoke)
            } catch (_: Exception) {}
            inMemoryAccessToken = null
            _authStatus.value = TasksAuthStatus.DISCONNECTED
        }

        if (revokeFailed) {
            Result.Error(failureException ?: IOException("Revoke failed"))
        } else {
            Result.Success(Unit)
        }
    }

    override fun markAuthRequired() {
        inMemoryAccessToken = null
        _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
    }
}
