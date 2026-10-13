package com.cayana.actions.notion.auth

import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

interface NotionAuthorizationManager {
    val authStatus: StateFlow<NotionAuthStatus>
    suspend fun getAccessToken(): Result<String>
    suspend fun getAuthorizationUrl(redirectUri: String? = null): Result<String>
    suspend fun handleAuthorizationCallback(code: String, state: String, redirectUri: String? = null): Result<Unit>
    suspend fun handleAuthorizationFailure(error: String): Result<Unit>
    suspend fun refreshAuthStatus(): NotionAuthStatus
    suspend fun disconnect(): Result<Unit>
    fun markAuthRequired()
}

class DefaultNotionAuthorizationManager(
    private val tokenStorage: NotionTokenStorage,
    private val brokerClient: NotionOAuthBrokerClient,
    private val settingsRepository: SettingsRepository,
    private val logger: CayanaLogger
) : NotionAuthorizationManager {

    companion object {
        private const val TAG = "NotionAuthManager"
    }

    private val _authStatus = MutableStateFlow(NotionAuthStatus.DISCONNECTED)
    override val authStatus: StateFlow<NotionAuthStatus> = _authStatus.asStateFlow()

    @Volatile
    private var pendingState: String? = null

    override suspend fun getAccessToken(): Result<String> {
        if (_authStatus.value == NotionAuthStatus.DISCONNECTED) {
            return Result.Error(NotionAuthRequiredException("Notion 尚未連線"))
        }

        val token = tokenStorage.getAccessToken()
        if (!token.isNullOrBlank()) {
            return Result.Success(token)
        }

        // Attempt refresh if refresh token is available
        val refreshToken = tokenStorage.getRefreshToken()
        if (!refreshToken.isNullOrBlank()) {
            logger.i(TAG, "Access token missing/expired; attempting token refresh with broker")
            when (val refreshResult = brokerClient.refreshToken(refreshToken)) {
                is Result.Success -> {
                    val resp = refreshResult.data
                    tokenStorage.saveTokens(
                        accessToken = resp.accessToken,
                        refreshToken = resp.refreshToken ?: refreshToken,
                        workspaceId = resp.workspaceId ?: tokenStorage.getWorkspaceId(),
                        workspaceName = resp.workspaceName ?: tokenStorage.getWorkspaceName(),
                        workspaceIcon = resp.workspaceIcon ?: tokenStorage.getWorkspaceIcon(),
                        botId = resp.botId ?: tokenStorage.getBotId()
                    )
                    _authStatus.value = NotionAuthStatus.CONNECTED
                    return Result.Success(resp.accessToken)
                }
                is Result.Error -> {
                    logger.w(TAG, "Token refresh failed: ${refreshResult.exception.javaClass.simpleName}")
                    markAuthRequired()
                    return Result.Error(NotionAuthRequiredException("Token refresh failed, authorization required"))
                }
                is Result.Loading -> {
                    return Result.Error(NotionAuthRequiredException("Token refresh in progress"))
                }
            }
        }

        markAuthRequired()
        return Result.Error(NotionAuthRequiredException("No access token available"))
    }

    override suspend fun getAuthorizationUrl(redirectUri: String?): Result<String> {
        return when (val initResult = brokerClient.initiateOAuth(redirectUri)) {
            is Result.Success -> {
                pendingState = initResult.data.state
                Result.Success(initResult.data.authorizeUrl)
            }
            is Result.Error -> {
                logger.w(TAG, "Failed initiating OAuth: ${initResult.exception.javaClass.simpleName}")
                Result.Error(initResult.exception)
            }
            is Result.Loading -> Result.Error(IllegalStateException("Initiate OAuth in progress"))
        }
    }

    override suspend fun handleAuthorizationCallback(
        code: String,
        state: String,
        redirectUri: String?
    ): Result<Unit> {
        val expectedState = pendingState
        if (expectedState.isNullOrBlank() || state != expectedState) {
            logger.w(TAG, "Security check failed: OAuth state mismatch or missing pending state")
            return Result.Error(SecurityException("OAuth state mismatch: state was not initiated by this client"))
        }

        return when (val exchangeResult = brokerClient.exchangeCode(code, state, redirectUri)) {
            is Result.Success -> {
                val data = exchangeResult.data
                tokenStorage.saveTokens(
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    workspaceId = data.workspaceId,
                    workspaceName = data.workspaceName,
                    workspaceIcon = data.workspaceIcon,
                    botId = data.botId
                )
                settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
                settingsRepository.updateNotionWorkspaceInfo(data.workspaceName, data.workspaceIcon)
                _authStatus.value = NotionAuthStatus.CONNECTED
                pendingState = null
                logger.i(TAG, "Notion authorization completed successfully")
                Result.Success(Unit)
            }
            is Result.Error -> {
                logger.w(TAG, "OAuth exchange failed: ${exchangeResult.exception.javaClass.simpleName}")
                pendingState = null
                Result.Error(exchangeResult.exception)
            }
            is Result.Loading -> Result.Error(IllegalStateException("OAuth exchange in progress"))
        }
    }

    override suspend fun handleAuthorizationFailure(error: String): Result<Unit> {
        logger.w(TAG, "Authorization was denied or failed: $error")
        pendingState = null
        if (_authStatus.value != NotionAuthStatus.CONNECTED) {
            _authStatus.value = NotionAuthStatus.DISCONNECTED
            settingsRepository.updateNotionAuthStatus(NotionAuthStatus.DISCONNECTED)
        }
        return Result.Success(Unit)
    }

    override suspend fun refreshAuthStatus(): NotionAuthStatus {
        val settings = runCatching { settingsRepository.getSettings().first() }.getOrNull()
        val hasTokens = tokenStorage.hasTokens()

        val resolved = when {
            settings?.notionAuthStatus == NotionAuthStatus.CONNECTED && hasTokens -> NotionAuthStatus.CONNECTED
            settings?.notionAuthStatus == NotionAuthStatus.AUTH_REQUIRED -> NotionAuthStatus.AUTH_REQUIRED
            hasTokens -> NotionAuthStatus.CONNECTED
            else -> NotionAuthStatus.DISCONNECTED
        }

        _authStatus.value = resolved
        if (settings?.notionAuthStatus != resolved) {
            settingsRepository.updateNotionAuthStatus(resolved)
        }
        return resolved
    }

    override suspend fun disconnect(): Result<Unit> {
        tokenStorage.clearTokens()
        pendingState = null
        _authStatus.value = NotionAuthStatus.DISCONNECTED
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.DISCONNECTED)
        settingsRepository.updateAutoNotionEnabled(false)
        settingsRepository.updateSelectedNotionTarget(null, null)
        settingsRepository.updateNotionWorkspaceInfo(null, null)
        logger.i(TAG, "Notion authorization disconnected and credentials purged")
        return Result.Success(Unit)
    }

    override fun markAuthRequired() {
        _authStatus.value = NotionAuthStatus.AUTH_REQUIRED
    }
}
