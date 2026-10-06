package com.cayana.backup.drive

import android.content.Context
import android.content.IntentSender
import com.cayana.backup.config.BackupConfig
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

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
    fun onAuthorizationSuccess(token: String)
    fun markAuthRequired()
    fun disconnect()
}

class GoogleDriveAuthorizationManager(
    private val context: Context
) : DriveAuthorizationManager {

    private val _authStatus = MutableStateFlow(DriveAuthStatus.DISCONNECTED)
    override val authStatus: StateFlow<DriveAuthStatus> = _authStatus.asStateFlow()

    // Short-lived, memory-only access token. Strictly never persisted to disk or logged.
    @Volatile
    private var inMemoryAccessToken: String? = null

    private val authorizationClient: AuthorizationClient by lazy {
        Identity.getAuthorizationClient(context)
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
                if (!token.isNullOrBlank()) {
                    inMemoryAccessToken = token
                    _authStatus.value = DriveAuthStatus.CONNECTED
                    Result.Success(token)
                } else {
                    _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
                    Result.Error(DriveAuthRequiredException("取得的 Access Token 為空。"))
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
                if (!token.isNullOrBlank()) {
                    inMemoryAccessToken = token
                    _authStatus.value = DriveAuthStatus.CONNECTED
                }
                Result.Success(null)
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override fun onAuthorizationSuccess(token: String) {
        inMemoryAccessToken = token
        _authStatus.value = DriveAuthStatus.CONNECTED
    }

    override fun markAuthRequired() {
        inMemoryAccessToken = null
        _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
    }

    override fun disconnect() {
        inMemoryAccessToken = null
        _authStatus.value = DriveAuthStatus.DISCONNECTED
    }
}

class FakeDriveAuthorizationManager(
    initialStatus: DriveAuthStatus = DriveAuthStatus.DISCONNECTED
) : DriveAuthorizationManager {

    private val _authStatus = MutableStateFlow(initialStatus)
    override val authStatus: StateFlow<DriveAuthStatus> = _authStatus.asStateFlow()

    var fakeAccessToken: String? = "fake_short_lived_token_12345"
    var requireResolution: Boolean = false

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

    override fun onAuthorizationSuccess(token: String) {
        fakeAccessToken = token
        requireResolution = false
        _authStatus.value = DriveAuthStatus.CONNECTED
    }

    override fun markAuthRequired() {
        _authStatus.value = DriveAuthStatus.AUTH_REQUIRED
    }

    override fun disconnect() {
        fakeAccessToken = null
        _authStatus.value = DriveAuthStatus.DISCONNECTED
    }
}
