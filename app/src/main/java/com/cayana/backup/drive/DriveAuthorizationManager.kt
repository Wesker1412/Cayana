package com.cayana.backup.drive

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.cayana.backup.config.BackupConfig
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

enum class DriveAuthStatus {
    DISCONNECTED,
    CONNECTED,
    AUTH_REQUIRED
}

class DriveAuthRequiredException(message: String = "Google Drive 授權已失效或需要使用者確認") : Exception(message)

interface DriveAuthorizationManager {
    val authStatus: StateFlow<DriveAuthStatus>
    suspend fun getAccessToken(): Result<String>
    suspend fun getAuthorizationIntentSender(): Result<IntentSender?>
    suspend fun handleAuthorizationResult(data: Intent?): Result<Unit>
    suspend fun invalidateCachedAccessToken()
    suspend fun revokeAuthorization(): Result<Unit>
    fun markAuthRequired()
}

class GoogleDriveAuthorizationManager(
    private val context: Context,
    private val authClientProvider: () -> AuthorizationClient = { Identity.getAuthorizationClient(context) }
) : DriveAuthorizationManager {

    private val _authStatus = MutableStateFlow(DriveAuthStatus.DISCONNECTED)
    override val authStatus: StateFlow<DriveAuthStatus> = _authStatus.asStateFlow()

    // Short-lived, memory-only access token. Strictly never persisted to disk or logged.
    @Volatile
    private var inMemoryAccessToken: String? = null

    private val authorizationClient: AuthorizationClient by lazy {
        authClientProvider()
    }

    companion object {
        fun buildAuthorizationRequest(): AuthorizationRequest {
            return AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(BackupConfig.DRIVE_SCOPE)))
                .build()
        }
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
                _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
                Result.Error(DriveAuthRequiredException())
            } else {
                val token = result.accessToken
                val scopes = result.grantedScopes ?: emptyList()
                val hasAppData = scopes.any { it.equals(BackupConfig.DRIVE_SCOPE, ignoreCase = true) }

                if (!token.isNullOrBlank() && hasAppData) {
                    inMemoryAccessToken = token
                    _authStatus.value = DriveAuthStatus.CONNECTED
                    Result.Success(token)
                } else {
                    _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
                    Result.Error(DriveAuthRequiredException("取得的 Access Token 為空或未被授予 drive.appdata 權限。"))
                }
            }
        } catch (e: Exception) {
            CayanaLogger.w("DriveAuth", "Failed to silently obtain Drive access token", e)
            _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
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
                val hasAppData = scopes.any { it.equals(BackupConfig.DRIVE_SCOPE, ignoreCase = true) }
                if (!token.isNullOrBlank() && hasAppData) {
                    inMemoryAccessToken = token
                    _authStatus.value = DriveAuthStatus.CONNECTED
                }
                Result.Success(null)
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        if (data == null) {
            _authStatus.value = DriveAuthStatus.DISCONNECTED
            return@withContext Result.Error(DriveAuthRequiredException("授權結果資料為空。"))
        }

        try {
            val authResult = authorizationClient.getAuthorizationResultFromIntent(data)
            val token = authResult.accessToken
            val grantedScopes = authResult.grantedScopes ?: emptyList()
            val hasAppData = grantedScopes.any { it.equals(BackupConfig.DRIVE_SCOPE, ignoreCase = true) }

            if (token.isNullOrBlank()) {
                _authStatus.value = DriveAuthStatus.DISCONNECTED
                Result.Error(DriveAuthRequiredException("授權結果中的 Access Token 為空。"))
            } else if (!hasAppData) {
                _authStatus.value = DriveAuthStatus.DISCONNECTED
                Result.Error(DriveAuthRequiredException("授權結果缺少必要的 drive.appdata 權限範圍。"))
            } else {
                inMemoryAccessToken = token
                _authStatus.value = DriveAuthStatus.CONNECTED
                Result.Success(Unit)
            }
        } catch (e: Exception) {
            CayanaLogger.w("DriveAuth", "解析授權 Intent 結果失敗", e)
            _authStatus.value = DriveAuthStatus.DISCONNECTED
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

    override suspend fun revokeAuthorization(): Result<Unit> = withContext(Dispatchers.IO) {
        val token = inMemoryAccessToken
        try {
            if (!token.isNullOrBlank()) {
                val url = URL("https://oauth2.googleapis.com/revoke?token=$token")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                }
                val responseCode = conn.responseCode
                if (responseCode !in 200..299 && responseCode != 400) {
                    return@withContext Result.Error(IOException("Google 權限撤銷失敗：HTTP $responseCode"))
                }
                try {
                    GoogleAuthUtil.clearToken(context, token)
                } catch (_: Exception) {}
            }
            inMemoryAccessToken = null
            _authStatus.value = DriveAuthStatus.DISCONNECTED
            Result.Success(Unit)
        } catch (e: Exception) {
            CayanaLogger.w("DriveAuth", "撤銷 Google Drive 授權失敗", e)
            Result.Error(e)
        }
    }

    override fun markAuthRequired() {
        inMemoryAccessToken = null
        _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
    }
}

open class FakeDriveAuthorizationManager(
    initialStatus: DriveAuthStatus = DriveAuthStatus.DISCONNECTED
) : DriveAuthorizationManager {

    private val _authStatus = MutableStateFlow(initialStatus)
    override val authStatus: StateFlow<DriveAuthStatus> = _authStatus.asStateFlow()

    var fakeAccessToken: String? = "fake_short_lived_token_12345"
    var requireResolution: Boolean = false
    var shouldRevokeFail: Boolean = false
    var lastRevokedToken: String? = null
    var onInvalidateToken: (() -> Unit)? = null

    fun setAuthorizedToken(token: String) {
        fakeAccessToken = token
        requireResolution = false
        _authStatus.value = DriveAuthStatus.CONNECTED
    }

    suspend fun disconnect(): Result<Unit> = revokeAuthorization()

    override suspend fun getAccessToken(): Result<String> {
        if (requireResolution || _authStatus.value == DriveAuthStatus.AUTH_REQUIRED) {
            _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
            return Result.Error(DriveAuthRequiredException())
        }
        val token = fakeAccessToken
        return if (!token.isNullOrBlank()) {
            _authStatus.value = DriveAuthStatus.CONNECTED
            Result.Success(token)
        } else {
            _authStatus.value = DriveAuthStatus.DISCONNECTED
            Result.Error(DriveAuthRequiredException("未登入"))
        }
    }

    override suspend fun getAuthorizationIntentSender(): Result<IntentSender?> {
        return Result.Success(null)
    }

    override suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> {
        if (data == null) {
            _authStatus.value = DriveAuthStatus.DISCONNECTED
            return Result.Error(DriveAuthRequiredException("Intent data is null"))
        }
        if (data.getBooleanExtra("fail_authorization", false)) {
            _authStatus.value = DriveAuthStatus.DISCONNECTED
            return Result.Error(DriveAuthRequiredException("Fake authorization failed"))
        }
        val token = data.getStringExtra("access_token") ?: fakeAccessToken
        val scopes = data.getStringArrayListExtra("granted_scopes") ?: arrayListOf(BackupConfig.DRIVE_SCOPE)

        if (token.isNullOrBlank()) {
            _authStatus.value = DriveAuthStatus.DISCONNECTED
            return Result.Error(DriveAuthRequiredException("Access token is empty"))
        }
        if (!scopes.contains(BackupConfig.DRIVE_SCOPE)) {
            _authStatus.value = DriveAuthStatus.DISCONNECTED
            return Result.Error(DriveAuthRequiredException("Missing drive.appdata scope"))
        }

        fakeAccessToken = token
        requireResolution = false
        _authStatus.value = DriveAuthStatus.CONNECTED
        return Result.Success(Unit)
    }

    override suspend fun invalidateCachedAccessToken() {
        fakeAccessToken = if (requireResolution) null else "fake_refreshed_token"
        onInvalidateToken?.invoke()
    }

    override suspend fun revokeAuthorization(): Result<Unit> {
        if (shouldRevokeFail) {
            return Result.Error(IOException("Revocation simulated failure"))
        }
        lastRevokedToken = fakeAccessToken
        fakeAccessToken = null
        _authStatus.value = DriveAuthStatus.DISCONNECTED
        return Result.Success(Unit)
    }

    override fun markAuthRequired() {
        fakeAccessToken = null
        _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
    }
}
