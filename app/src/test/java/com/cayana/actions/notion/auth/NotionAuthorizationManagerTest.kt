package com.cayana.actions.notion.auth

import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotionAuthorizationManagerTest {

    private lateinit var tokenStorage: FakeNotionTokenStorage
    private lateinit var brokerClient: FakeNotionOAuthBrokerClient
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var logger: FakeCayanaLogger
    private lateinit var authManager: DefaultNotionAuthorizationManager

    @Before
    fun setup() {
        tokenStorage = FakeNotionTokenStorage()
        brokerClient = FakeNotionOAuthBrokerClient()
        settingsRepository = InMemorySettingsRepository()
        logger = FakeCayanaLogger()
        authManager = DefaultNotionAuthorizationManager(
            tokenStorage = tokenStorage,
            brokerClient = brokerClient,
            settingsRepository = settingsRepository,
            logger = logger
        )
    }

    @Test
    fun initialStatusIsDisconnected() = runTest {
        assertEquals(NotionAuthStatus.DISCONNECTED, authManager.authStatus.value)
    }

    @Test
    fun getAuthorizationUrlGeneratesUrlAndSetsPendingStateAndVerifier() = runTest {
        brokerClient.initiateResponse = NotionOAuthInitiateResponse(
            authorizeUrl = "https://api.notion.com/v1/oauth/authorize?state=test_state_123",
            state = "test_state_123"
        )

        val result = authManager.getAuthorizationUrl()
        assertTrue(result is Result.Success)
        val url = (result as Result.Success).data
        assertTrue(url.contains("state=test_state_123"))

        // Verify PKCE code challenge was passed to broker
        assertNotNull(brokerClient.lastCodeChallenge)
        assertTrue(brokerClient.lastCodeChallenge!!.isNotBlank())

        // Verify pending transaction was persisted to storage
        assertEquals("test_state_123", tokenStorage.getPendingOAuthState())
        assertNotNull(tokenStorage.getPendingCodeVerifier())
    }

    @Test
    fun oauthInitiationStorageFailureFailsClosed() = runTest {
        brokerClient.initiateResponse = NotionOAuthInitiateResponse(
            authorizeUrl = "https://api.notion.com/v1/oauth/authorize?state=test_state",
            state = "test_state"
        )
        tokenStorage.failSavePendingTransaction = true

        val result = authManager.getAuthorizationUrl()

        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).exception is SecurityException)
        assertNull(tokenStorage.getPendingOAuthState())
    }

    @Test
    fun callbackWithStateMismatchThrowsSecurityException() = runTest {
        brokerClient.initiateResponse = NotionOAuthInitiateResponse(
            authorizeUrl = "https://api.notion.com/v1/oauth/authorize?state=correct_state",
            state = "correct_state"
        )
        authManager.getAuthorizationUrl()

        val callbackResult = authManager.handleAuthorizationCallback(
            ticket = "ticket_123",
            state = "evil_hacker_state"
        )

        assertTrue(callbackResult is Result.Error)
        val error = (callbackResult as Result.Error).exception
        assertTrue(error is SecurityException)
        assertFalse(tokenStorage.hasTokens())
        assertNull(tokenStorage.getPendingOAuthState())
    }

    @Test
    fun unsolicitedCallbackFailsClosed() = runTest {
        // No initiation performed: pendingState and persisted transaction are null
        assertNull(tokenStorage.getPendingOAuthState())

        val callbackResult = authManager.handleAuthorizationCallback(
            ticket = "unsolicited_ticket",
            state = "unsolicited_state"
        )

        assertTrue(callbackResult is Result.Error)
        assertTrue((callbackResult as Result.Error).exception is SecurityException)
        assertEquals(NotionAuthStatus.DISCONNECTED, authManager.authStatus.value)
    }

    @Test
    fun callbackWithValidTicketExchangesAndPersistsTokens() = runTest {
        val expectedState = "safe_state_xyz"
        brokerClient.initiateResponse = NotionOAuthInitiateResponse(
            authorizeUrl = "https://api.notion.com/v1/oauth/authorize?state=$expectedState",
            state = expectedState
        )
        authManager.getAuthorizationUrl()

        brokerClient.tokenResponse = NotionOAuthTokenResponse(
            accessToken = "secret_notion_access_token_12345",
            refreshToken = "secret_notion_refresh_token_67890",
            workspaceId = "workspace_abc",
            workspaceName = "My Workspace",
            workspaceIcon = "https://icon.url",
            botId = "bot_123"
        )

        val result = authManager.handleAuthorizationCallback(
            ticket = "valid_relay_ticket",
            state = expectedState
        )

        assertTrue(result is Result.Success)
        assertTrue(tokenStorage.hasTokens())
        assertEquals("secret_notion_access_token_12345", tokenStorage.getAccessToken())
        assertEquals("secret_notion_refresh_token_67890", tokenStorage.getRefreshToken())
        assertEquals("workspace_abc", tokenStorage.getWorkspaceId())
        assertEquals("My Workspace", tokenStorage.getWorkspaceName())

        // Verify pending transaction was cleared
        assertNull(tokenStorage.getPendingOAuthState())
        assertNull(tokenStorage.getPendingCodeVerifier())

        // Verify status updated in manager and settings repository
        assertEquals(NotionAuthStatus.CONNECTED, authManager.authStatus.value)
        val settings = settingsRepository.getSettings().first()
        assertTrue(settings.isNotionConnected)
        assertEquals(NotionAuthStatus.CONNECTED, settings.notionAuthStatus)
        assertEquals("My Workspace", settings.notionWorkspaceName)
    }

    @Test
    fun callbackAfterProcessRestartHandledSafely() = runTest {
        // 1. App initiates OAuth transaction
        val state = "persisted_state_789"
        brokerClient.initiateResponse = NotionOAuthInitiateResponse(
            authorizeUrl = "https://api.notion.com/v1/oauth/authorize?state=$state",
            state = state
        )
        authManager.getAuthorizationUrl()

        // Verify state and verifier are safely persisted in token storage
        val persistedVerifier = tokenStorage.getPendingCodeVerifier()
        assertNotNull(persistedVerifier)
        assertEquals(state, tokenStorage.getPendingOAuthState())

        // 2. Simulate process death & app restart: re-instantiate DefaultNotionAuthorizationManager
        val newAuthManager = DefaultNotionAuthorizationManager(
            tokenStorage = tokenStorage,
            brokerClient = brokerClient,
            settingsRepository = settingsRepository,
            logger = logger
        )

        brokerClient.tokenResponse = NotionOAuthTokenResponse(
            accessToken = "restored_access_token",
            refreshToken = "restored_refresh_token",
            workspaceId = "ws_restored",
            workspaceName = "Restored Workspace",
            workspaceIcon = null,
            botId = null
        )

        // 3. Callback arrives after process restart
        val callbackResult = newAuthManager.handleAuthorizationCallback(
            ticket = "relay_ticket_after_restart",
            state = state
        )

        assertTrue(callbackResult is Result.Success)
        assertEquals("relay_ticket_after_restart", brokerClient.lastTicket)
        assertEquals(persistedVerifier, brokerClient.lastCodeVerifier)
        assertEquals(NotionAuthStatus.CONNECTED, newAuthManager.authStatus.value)
        assertEquals("restored_access_token", tokenStorage.getAccessToken())
        assertNull(tokenStorage.getPendingOAuthState())
    }

    @Test
    fun tokenStorageFailureCannotMarkConnected() = runTest {
        val state = "state_keystore_fail"
        brokerClient.initiateResponse = NotionOAuthInitiateResponse(
            authorizeUrl = "https://api.notion.com/v1/oauth/authorize?state=$state",
            state = state
        )
        authManager.getAuthorizationUrl()

        brokerClient.tokenResponse = NotionOAuthTokenResponse(
            accessToken = "token_wont_save",
            refreshToken = "refresh_wont_save",
            workspaceId = "ws_fail",
            workspaceName = "Fail WS",
            workspaceIcon = null,
            botId = null
        )

        // Simulate Keystore encryption failure / persistence failure
        tokenStorage.failSaveTokens = true

        val result = authManager.handleAuthorizationCallback(
            ticket = "ticket_fail_store",
            state = state
        )

        // Must fail-closed and return error
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).exception is SecurityException)

        // Auth status MUST remain DISCONNECTED
        assertEquals(NotionAuthStatus.DISCONNECTED, authManager.authStatus.value)
        val settings = settingsRepository.getSettings().first()
        assertFalse(settings.isNotionConnected)
        assertEquals(NotionAuthStatus.DISCONNECTED, settings.notionAuthStatus)
        assertFalse(tokenStorage.hasTokens())
    }

    @Test
    fun failureCallbackSetsStatusToDisconnected() = runTest {
        authManager.handleAuthorizationFailure("User canceled authorization")

        assertEquals(NotionAuthStatus.DISCONNECTED, authManager.authStatus.value)
        val settings = settingsRepository.getSettings().first()
        assertFalse(settings.isNotionConnected)
        assertEquals(NotionAuthStatus.DISCONNECTED, settings.notionAuthStatus)
    }

    @Test
    fun getAccessTokenReturnsStoredTokenWhenAvailable() = runTest {
        tokenStorage.saveTokens(
            accessToken = "active_token_123",
            refreshToken = "refresh_token_123"
        )
        authManager.refreshAuthStatus()

        val tokenResult = authManager.getAccessToken()
        assertTrue(tokenResult is Result.Success)
        assertEquals("active_token_123", (tokenResult as Result.Success).data)
    }

    @Test
    fun getAccessTokenRefreshesWhenAccessTokenExpiredOrEmpty() = runTest {
        tokenStorage.saveTokens(
            accessToken = "",
            refreshToken = "valid_refresh_token"
        )
        authManager.refreshAuthStatus()

        brokerClient.tokenResponse = NotionOAuthTokenResponse(
            accessToken = "brand_new_access_token_777",
            refreshToken = "valid_refresh_token",
            workspaceId = "ws_1",
            workspaceName = "Workspace",
            workspaceIcon = null,
            botId = null
        )

        val tokenResult = authManager.getAccessToken()
        assertTrue(tokenResult is Result.Success)
        assertEquals("brand_new_access_token_777", (tokenResult as Result.Success).data)
        assertEquals("brand_new_access_token_777", tokenStorage.getAccessToken())
        assertEquals(NotionAuthStatus.CONNECTED, authManager.authStatus.value)
    }

    @Test
    fun getAccessTokenFailsAndMarksAuthRequiredWhenRefreshFails() = runTest {
        tokenStorage.saveTokens(
            accessToken = "",
            refreshToken = "expired_refresh_token"
        )
        authManager.refreshAuthStatus()

        brokerClient.refreshFailure = RuntimeException("Token refresh rejected by broker")

        val tokenResult = authManager.getAccessToken()
        assertTrue(tokenResult is Result.Error)
        assertEquals(NotionAuthStatus.AUTH_REQUIRED, authManager.authStatus.value)
    }

    @Test
    fun disconnectClearsTokensAndResetsSettings() = runTest {
        tokenStorage.saveTokens(
            accessToken = "live_token",
            refreshToken = "live_refresh",
            workspaceName = "My WS"
        )
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateAutoNotionEnabled(true)
        settingsRepository.updateSelectedNotionTarget("target_123", "Target Page")
        authManager.refreshAuthStatus()

        val disconnectResult = authManager.disconnect()
        assertTrue(disconnectResult is Result.Success)

        assertFalse(tokenStorage.hasTokens())
        assertEquals(NotionAuthStatus.DISCONNECTED, authManager.authStatus.value)

        val settings = settingsRepository.getSettings().first()
        assertFalse(settings.isNotionConnected)
        assertFalse(settings.autoNotionEnabled)
        assertEquals(null, settings.selectedNotionTargetId)
        assertEquals(null, settings.notionWorkspaceName)
    }

    @Test
    fun oauthErrorsNeverLeakCredentials() = runTest {
        val sensitiveAccessToken = "secret_access_xyz999"
        val sensitiveRefreshToken = "secret_refresh_abc888"
        val sensitiveSecret = "secret_super_private_credential_key"

        brokerClient.initiateResponse = NotionOAuthInitiateResponse("https://auth.url?state=s", "s")
        authManager.getAuthorizationUrl()

        // Simulate broker exchange failure with error message
        brokerClient.exchangeFailure = RuntimeException("Authentication failed for client_secret=$sensitiveSecret")
        authManager.handleAuthorizationCallback("ticket", "s")

        // Also test failure callback
        authManager.handleAuthorizationFailure("Error with token=$sensitiveAccessToken and secret=$sensitiveSecret")

        for (log in logger.logs) {
            assertFalse("Access token must never appear in log: $log", log.contains(sensitiveAccessToken))
            assertFalse("Refresh token must never appear in log: $log", log.contains(sensitiveRefreshToken))
            assertFalse("Secret credential must never appear in log: $log", log.contains(sensitiveSecret))
        }
    }
}

class FakeNotionTokenStorage : NotionTokenStorage {
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var workspaceId: String? = null
    private var workspaceName: String? = null
    private var workspaceIcon: String? = null
    private var botId: String? = null

    private var pendingState: String? = null
    private var pendingVerifier: String? = null

    var failSaveTokens: Boolean = false

    override fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        workspaceId: String?,
        workspaceName: String?,
        workspaceIcon: String?,
        botId: String?
    ): Boolean {
        if (failSaveTokens) {
            return false
        }
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.workspaceId = workspaceId
        this.workspaceName = workspaceName
        this.workspaceIcon = workspaceIcon
        this.botId = botId
        return true
    }

    override fun getAccessToken(): String? = accessToken
    override fun getRefreshToken(): String? = refreshToken
    override fun getWorkspaceId(): String? = workspaceId
    override fun getWorkspaceName(): String? = workspaceName
    override fun getWorkspaceIcon(): String? = workspaceIcon
    override fun getBotId(): String? = botId

    override fun clearTokens() {
        accessToken = null
        refreshToken = null
        workspaceId = null
        workspaceName = null
        workspaceIcon = null
        botId = null
    }

    override fun hasTokens(): Boolean = !accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank()

    var failSavePendingTransaction: Boolean = false

    override fun savePendingOAuthTransaction(state: String, codeVerifier: String): Boolean {
        if (failSavePendingTransaction) return false
        this.pendingState = state
        this.pendingVerifier = codeVerifier
        return true
    }

    override fun getPendingOAuthState(): String? = pendingState
    override fun getPendingCodeVerifier(): String? = pendingVerifier

    override fun clearPendingOAuthTransaction() {
        pendingState = null
        pendingVerifier = null
    }
}

class FakeNotionOAuthBrokerClient : NotionOAuthBrokerClient {
    var initiateResponse: NotionOAuthInitiateResponse? = null
    var initiateFailure: Throwable? = null
    var tokenResponse: NotionOAuthTokenResponse? = null
    var exchangeFailure: Throwable? = null
    var refreshFailure: Throwable? = null

    var lastCodeChallenge: String? = null
    var lastTicket: String? = null
    var lastState: String? = null
    var lastCodeVerifier: String? = null

    override suspend fun initiateOAuth(codeChallenge: String, redirectUri: String?): Result<NotionOAuthInitiateResponse> {
        lastCodeChallenge = codeChallenge
        initiateFailure?.let { return Result.Error(it) }
        val resp = initiateResponse ?: NotionOAuthInitiateResponse("https://api.notion.com/v1/oauth/authorize?state=fake", "fake")
        return Result.Success(resp)
    }

    override suspend fun exchangeTicket(
        ticket: String,
        state: String,
        codeVerifier: String
    ): Result<NotionOAuthTokenResponse> {
        lastTicket = ticket
        lastState = state
        lastCodeVerifier = codeVerifier
        exchangeFailure?.let { return Result.Error(it) }
        val resp = tokenResponse ?: NotionOAuthTokenResponse("fake_access", "fake_refresh", "ws", "Workspace", null, null)
        return Result.Success(resp)
    }

    override suspend fun refreshToken(refreshToken: String): Result<NotionOAuthTokenResponse> {
        refreshFailure?.let { return Result.Error(it) }
        val resp = tokenResponse ?: NotionOAuthTokenResponse("fake_refreshed_access", refreshToken, "ws", "Workspace", null, null)
        return Result.Success(resp)
    }
}

class FakeCayanaLogger : CayanaLogger {
    val logs = mutableListOf<String>()

    override fun d(tag: String, message: String) {
        logs.add("DEBUG: [$tag] $message")
    }

    override fun i(tag: String, message: String) {
        logs.add("INFO: [$tag] $message")
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        logs.add("WARN: [$tag] $message ${throwable?.message ?: ""}")
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        logs.add("ERROR: [$tag] $message ${throwable?.message ?: ""}")
    }

    override fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?) {
        logs.add("MEMORY: [$tag] $eventName $memoryId")
    }
}
