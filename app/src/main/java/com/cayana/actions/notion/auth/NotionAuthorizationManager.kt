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
    suspend fun handleAuthorizationCallback(ticket: String, state: String, redirectUri: String? = null): Result<Unit>
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

    @Volatile
    private var pendingCodeVerifier: String? = null

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
                    val saved = tokenStorage.saveTokens(
                        accessToken = resp.accessToken,
                        refreshToken = resp.refreshToken ?: refreshToken,
                        workspaceId = resp.workspaceId ?: tokenStorage.getWorkspaceId(),
                        workspaceName = resp.workspaceName ?: tokenStorage.getWorkspaceName(),
                        workspaceIcon = resp.workspaceIcon ?: tokenStorage.getWorkspaceIcon(),
                        botId = resp.botId ?: tokenStorage.getBotId()
                    )
                    if (!saved) {
                        logger.e(TAG, "Failed to securely save refreshed tokens; marking auth required")
                        markAuthRequired()
                        return Result.Error(NotionAuthRequiredException("Failed to securely store refreshed tokens"))
                    }
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
        val verifier = PkceUtil.generateCodeVerifier()
        val challenge = PkceUtil.generateCodeChallenge(verifier)

        return when (val initResult = brokerClient.initiateOAuth(challenge, redirectUri)) {
            is Result.Success -> {
                val state = initResult.data.state
                pendingState = state
                pendingCodeVerifier = verifier
                tokenStorage.savePendingOAuthTransaction(state, verifier)
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
        ticket: String,
        state: String,
        redirectUri: String?
    ): Result<Unit> {
        val expectedState = pendingState ?: tokenStorage.getPendingOAuthState()
        val codeVerifier = pendingCodeVerifier ?: tokenStorage.getPendingCodeVerifier()

        if (expectedState.isNullOrBlank() || state != expectedState || codeVerifier.isNullOrBlank()) {
            logger.w(TAG, "Security check failed: OAuth state mismatch or missing pending state/verifier")
            pendingState = null
            pendingCodeVerifier = null
            tokenStorage.clearPendingOAuthTransaction()
            return Result.Error(SecurityException("OAuth state mismatch: state was not initiated by this client"))
        }

        // Atomically consume pending transaction locally to prevent replay
        pendingState = null
        pendingCodeVerifier = null
        tokenStorage.clearPendingOAuthTransaction()

        return when (val exchangeResult = brokerClient.exchangeTicket(ticket, state, codeVerifier)) {
            is Result.Success -> {
                val data = exchangeResult.data
                val saved = tokenStorage.saveTokens(
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    workspaceId = data.workspaceId,
                    workspaceName = data.workspaceName,
                    workspaceIcon = data.workspaceIcon,
                    botId = data.botId
                )
                if (!saved) {
                    logger.e(TAG, "Failed to securely save Notion tokens in Keystore; failing closed")
                    tokenStorage.clearTokens()
                    _authStatus.value = NotionAuthStatus.DISCONNECTED
                    settingsRepository.updateNotionAuthStatus(NotionAuthStatus.DISCONNECTED)
                    return Result.Error(SecurityException("Failed to securely encrypt/persist Notion tokens"))
                }

                settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
                settingsRepository.updateNotionWorkspaceInfo(data.workspaceName, data.workspaceIcon)
                _authStatus.value = NotionAuthStatus.CONNECTED
                logger.i(TAG, "Notion authorization completed successfully")
                Result.Success(Unit)
            }
            is Result.Error -> {
                logger.w(TAG, "OAuth exchange failed: ${exchangeResult.exception.javaClass.simpleName}")
                _authStatus.value = NotionAuthStatus.DISCONNECTED
                settingsRepository.updateNotionAuthStatus(NotionAuthStatus.DISCONNECTED)
                Result.Error(exchangeResult.exception)
            }
            is Result.Loading -> Result.Error(IllegalStateException("OAuth exchange in progress"))
        }
    }

    override suspend fun handleAuthorizationFailure(error: String): Result<Unit> {
        val structuredCode = when {
            error.contains("access_denied", ignoreCase = true) || error.contains("cancel", ignoreCase = true) -> "ACCESS_DENIED"
            error.contains("invalid_request", ignoreCase = true) -> "INVALID_REQUEST"
            error.contains("unauthorized", ignoreCase = true) -> "UNAUTHORIZED"
            else -> "OAUTH_CALLBACK_ERROR"
        }
        logger.w(TAG, "Authorization was denied or failed: errorCode=$structuredCode")
        pendingState = null
        pendingCodeVerifier = null
        tokenStorage.clearPendingOAuthTransaction()
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
        tokenStorage.clearPendingOAuthTransaction()
        pendingState = null
        pendingCodeVerifier = null
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
