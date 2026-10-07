package com.cayana.cloud.auth

import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class CloudAuthStatus {
    UNINITIALIZED,
    AUTHENTICATED,
    NEEDS_ATTENTION
}

interface CloudAuthManager {
    val authStatus: StateFlow<CloudAuthStatus>
    fun getUserId(): String?
    fun hasStoredRefreshToken(): Boolean
    suspend fun getValidAccessToken(): Result<String>
    suspend fun initialSignInAnonymously(): Result<String>
    suspend fun restoreSession(): Result<String>
    suspend fun refreshSession(): Result<String>
    suspend fun clearSession()
}

class SupabaseCloudAuthManager(
    private val supabaseClient: SupabaseClient,
    private val refreshTokenStorage: RefreshTokenStorage
) : CloudAuthManager {

    private val _authStatus = MutableStateFlow(CloudAuthStatus.UNINITIALIZED)
    override val authStatus: StateFlow<CloudAuthStatus> = _authStatus.asStateFlow()

    // Access Token is strictly in-memory only. Never logged or written to disk.
    @Volatile
    private var inMemoryAccessToken: String? = null

    @Volatile
    private var inMemoryUserId: String? = null

    override fun getUserId(): String? = inMemoryUserId

    override fun hasStoredRefreshToken(): Boolean = refreshTokenStorage.hasRefreshToken()

    override suspend fun initialSignInAnonymously(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val auth = supabaseClient.auth
            auth.signInAnonymously()
            val session = auth.currentSessionOrNull()
            if (session != null) {
                val token = session.accessToken
                val refreshToken = session.refreshToken
                val user = session.user

                if (!token.isNullOrBlank() && !refreshToken.isNullOrBlank() && user != null) {
                    inMemoryAccessToken = token
                    inMemoryUserId = user.id
                    refreshTokenStorage.saveRefreshToken(refreshToken)
                    _authStatus.value = CloudAuthStatus.AUTHENTICATED
                    CayanaLogger.i("CloudAuth", "Anonymous session created successfully")
                    Result.Success(token)
                } else {
                    _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
                    Result.Error(IllegalStateException("Anonymous session missing token or user"))
                }
            } else {
                _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
                Result.Error(IllegalStateException("No session returned from signInAnonymously"))
            }
        } catch (e: Exception) {
            CayanaLogger.w("CloudAuth", "Anonymous sign in failed", e)
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            Result.Error(e)
        }
    }

    override suspend fun restoreSession(): Result<String> = withContext(Dispatchers.IO) {
        val storedRefreshToken = refreshTokenStorage.loadRefreshToken()
        if (storedRefreshToken.isNullOrBlank()) {
            _authStatus.value = CloudAuthStatus.UNINITIALIZED
            return@withContext Result.Error(IllegalStateException("No stored refresh token"))
        }

        try {
            val auth = supabaseClient.auth
            val session = auth.refreshSession(storedRefreshToken)
            if (!session.accessToken.isNullOrBlank() && session.user != null) {
                inMemoryAccessToken = session.accessToken
                inMemoryUserId = session.user?.id
                if (!session.refreshToken.isNullOrBlank()) {
                    refreshTokenStorage.saveRefreshToken(session.refreshToken)
                }
                _authStatus.value = CloudAuthStatus.AUTHENTICATED
                Result.Success(session.accessToken)
            } else {
                _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
                Result.Error(IllegalStateException("Failed to refresh session with stored token"))
            }
        } catch (e: Exception) {
            CayanaLogger.w("CloudAuth", "Failed to restore session from refresh token", e)
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            Result.Error(e)
        }
    }

    override suspend fun refreshSession(): Result<String> = withContext(Dispatchers.IO) {
        val storedRefreshToken = refreshTokenStorage.loadRefreshToken()
        if (storedRefreshToken.isNullOrBlank()) {
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            return@withContext Result.Error(IllegalStateException("No refresh token available to refresh"))
        }

        try {
            val auth = supabaseClient.auth
            val session = auth.refreshSession(storedRefreshToken)
            if (!session.accessToken.isNullOrBlank() && session.user != null) {
                inMemoryAccessToken = session.accessToken
                inMemoryUserId = session.user?.id
                if (!session.refreshToken.isNullOrBlank()) {
                    refreshTokenStorage.saveRefreshToken(session.refreshToken)
                }
                _authStatus.value = CloudAuthStatus.AUTHENTICATED
                Result.Success(session.accessToken)
            } else {
                _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
                Result.Error(IllegalStateException("Failed to refresh session"))
            }
        } catch (e: Exception) {
            CayanaLogger.w("CloudAuth", "Failed to refresh session", e)
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            Result.Error(e)
        }
    }

    override suspend fun getValidAccessToken(): Result<String> {
        val cached = inMemoryAccessToken
        if (!cached.isNullOrBlank()) {
            return Result.Success(cached)
        }
        // Attempt restoring/refreshing with saved refresh token
        return restoreSession()
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        inMemoryAccessToken = null
        inMemoryUserId = null
        refreshTokenStorage.clearRefreshToken()
        try {
            supabaseClient.auth.clearSession()
        } catch (_: Exception) {}
        _authStatus.value = CloudAuthStatus.UNINITIALIZED
    }
}
