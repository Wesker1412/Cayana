package com.cayana.cloud.auth

import com.cayana.core.common.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class FakeCloudAuthManager(
    initialStatus: CloudAuthStatus = CloudAuthStatus.UNINITIALIZED,
    private var simulatedUserId: String = UUID.randomUUID().toString()
) : CloudAuthManager {

    private val _authStatus = MutableStateFlow(initialStatus)
    override val authStatus: StateFlow<CloudAuthStatus> = _authStatus.asStateFlow()

    var fakeAccessToken: String? = "fake_jwt_token_for_tenant"
    var shouldFailAuth: Boolean = false
    var shouldFailRefresh: Boolean = false
    var hasRefreshToken: Boolean = false
    var initialSignInAnonymouslyCallCount: Int = 0

    fun setSimulatedUserId(userId: String) {
        simulatedUserId = userId
    }

    override fun getUserId(): String? = if (_authStatus.value == CloudAuthStatus.AUTHENTICATED) simulatedUserId else null

    override fun hasStoredRefreshToken(): Boolean = hasRefreshToken

    override suspend fun initialSignInAnonymously(): Result<String> {
        initialSignInAnonymouslyCallCount++
        if (shouldFailAuth) {
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            return Result.Error(IllegalStateException("Simulated auth failure"))
        }
        val token = fakeAccessToken ?: "fake_jwt_token_${simulatedUserId}"
        _authStatus.value = CloudAuthStatus.AUTHENTICATED
        return Result.Success(token)
    }

    override suspend fun restoreSession(): Result<String> {
        if (shouldFailRefresh || shouldFailAuth) {
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            return Result.Error(IllegalStateException("Simulated restore failure"))
        }
        val token = fakeAccessToken ?: "fake_jwt_token_${simulatedUserId}"
        _authStatus.value = CloudAuthStatus.AUTHENTICATED
        return Result.Success(token)
    }

    override suspend fun refreshSession(): Result<String> {
        if (shouldFailRefresh) {
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            return Result.Error(IllegalStateException("Simulated refresh failure"))
        }
        val token = fakeAccessToken ?: "fake_jwt_token_${simulatedUserId}"
        _authStatus.value = CloudAuthStatus.AUTHENTICATED
        return Result.Success(token)
    }

    override suspend fun getValidAccessToken(): Result<String> {
        if (shouldFailAuth) {
            _authStatus.value = CloudAuthStatus.NEEDS_ATTENTION
            return Result.Error(IllegalStateException("Simulated auth failure"))
        }
        val token = fakeAccessToken
        return if (!token.isNullOrBlank() && _authStatus.value == CloudAuthStatus.AUTHENTICATED) {
            Result.Success(token)
        } else {
            restoreSession()
        }
    }

    override suspend fun clearSession() {
        _authStatus.value = CloudAuthStatus.UNINITIALIZED
    }
}
