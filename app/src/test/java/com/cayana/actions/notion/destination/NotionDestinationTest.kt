package com.cayana.actions.notion.destination

import com.cayana.actions.destination.DefaultDestinationRegistry
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.RoutingDecision
import com.cayana.actions.notion.auth.FakeCayanaLogger
import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.actions.notion.auth.NotionAuthStatus
import com.cayana.actions.notion.auth.NotionAuthorizationManager
import com.cayana.actions.notion.client.NotionClient
import com.cayana.actions.notion.client.NotionCreatedPage
import com.cayana.actions.notion.client.NotionSearchResponse
import com.cayana.actions.notion.client.NotionTarget
import com.cayana.actions.notion.client.NotionTargetType
import com.cayana.actions.router.DefaultActionRouter
import com.cayana.actions.router.DefaultActionRoutingHistoryTracker
import com.cayana.actions.classifier.ActionClassifier
import com.cayana.core.common.Result
import com.cayana.memory.model.MemoryItem
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class NotionDestinationTest {

    private lateinit var authManager: FakeNotionAuthManager
    private lateinit var notionClient: FakeNotionClient
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var logger: FakeCayanaLogger
    private lateinit var destination: NotionDestination

    @Before
    fun setup() {
        authManager = FakeNotionAuthManager()
        notionClient = FakeNotionClient()
        settingsRepository = InMemorySettingsRepository()
        logger = FakeCayanaLogger()
        destination = NotionDestination(
            notionAuthManager = authManager,
            notionClient = notionClient,
            settingsRepository = settingsRepository,
            logger = logger
        )
    }

    @Test
    fun notionDestinationDefaultIsOffAndUnconfigured() = runTest {
        assertFalse(destination.isConfigured())
        assertFalse(destination.isAuthorized())
    }

    @Test
    fun unconfiguredDestinationNeverUploadsContent() = runTest {
        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "My Private Note",
            notes = "Super sensitive local memory text",
            confidence = 0.95f,
            sourceMemoryId = "mem_123"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.Failure)
        assertFalse((result as DestinationExecutionResult.Failure).canRetry)
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun unauthorizedDestinationReturnsNeedsAttentionAndNeverUploads() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateAutoNotionEnabled(true)
        settingsRepository.updateSelectedNotionTarget("target_page_1", "Notes Page")

        authManager.setAuthStatus(NotionAuthStatus.DISCONNECTED)

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Meeting Summary",
            notes = "Discussed roadmap",
            confidence = 0.9f,
            sourceMemoryId = "mem_456"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.NeedsAttention)
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun stage9c1CannotCreateRemotePage() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateAutoNotionEnabled(true)
        settingsRepository.updateSelectedNotionTarget("target_page_1", "Notes Page", "page")

        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "valid_bearer_token_abc"

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Project Roadmap",
            notes = "Release MVP next week",
            confidence = 0.95f,
            sourceMemoryId = "mem_789"
        )

        val result = destination.createNote(candidate)

        // Stage 9C-1 Boundary: fails closed without calling remote Notion client
        assertTrue(result is DestinationExecutionResult.Failure)
        val failure = result as DestinationExecutionResult.Failure
        assertFalse(failure.canRetry)
        assertTrue(failure.reason.contains("Stage 9C-1"))
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun tokenNeverAppearsInLogs() = runTest {
        val secretToken = "super_secret_bearer_987654321"

        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateAutoNotionEnabled(true)
        settingsRepository.updateSelectedNotionTarget("target_page_1", "Notes Page")

        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = secretToken

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Log Privacy Test",
            notes = "Content",
            confidence = 0.9f,
            sourceMemoryId = "mem_privacy"
        )

        destination.createNote(candidate)

        for (log in logger.logs) {
            assertFalse("Token must never leak into logs: $log", log.contains(secretToken))
        }
    }

    @Test
    fun actionRouterEvaluatesNotionAsMemoryOnlyWhenUnconfigured() = runTest {
        val registry = DefaultDestinationRegistry(listOf(destination))
        val fakeClassifier = object : ActionClassifier {
            override fun classify(
                memoryItem: MemoryItem,
                rawText: String?,
                referenceTime: Instant,
                zoneId: ZoneId
            ): List<ActionCandidate> {
                return listOf(
                    ActionCandidate(
                        type = ActionCandidateType.NOTE,
                        title = "Unconfigured Test Note",
                        notes = "Note content",
                        confidence = 0.92f,
                        sourceMemoryId = memoryItem.id
                    )
                )
            }
        }

        val router = DefaultActionRouter(
            classifier = fakeClassifier,
            destinationRegistry = registry,
            routingHistoryTracker = DefaultActionRoutingHistoryTracker(),
            logger = logger
        )

        val memoryItem = MemoryItem(
            id = "mem_unconf",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 1000L,
            rawText = "Note test"
        )

        val decisions = router.route(memoryItem, "Note test", Instant.now(), ZoneId.systemDefault())

        assertEquals(1, decisions.size)
        assertTrue(decisions[0] is RoutingDecision.MemoryOnly)
        assertEquals("Destination for NOTE is not configured", (decisions[0] as RoutingDecision.MemoryOnly).reason)
    }
}

class FakeNotionAuthManager : NotionAuthorizationManager {
    private val _authStatus = MutableStateFlow(NotionAuthStatus.DISCONNECTED)
    override val authStatus: StateFlow<NotionAuthStatus> = _authStatus.asStateFlow()

    var tokenToReturn: String? = null
    var tokenFailure: Throwable? = null

    fun setAuthStatus(status: NotionAuthStatus) {
        _authStatus.value = status
    }

    override suspend fun getAccessToken(): Result<String> {
        tokenFailure?.let { return Result.Error(it) }
        val token = tokenToReturn
        return if (!token.isNullOrBlank()) {
            Result.Success(token)
        } else {
            Result.Error(NotionAuthRequiredException("No access token"))
        }
    }

    override suspend fun getAuthorizationUrl(redirectUri: String?): Result<String> {
        return Result.Success("https://api.notion.com/v1/oauth/authorize?state=fake")
    }

    override suspend fun handleAuthorizationCallback(
        code: String,
        state: String,
        redirectUri: String?
    ): Result<Unit> {
        _authStatus.value = NotionAuthStatus.CONNECTED
        return Result.Success(Unit)
    }

    override suspend fun handleAuthorizationFailure(error: String): Result<Unit> {
        _authStatus.value = NotionAuthStatus.DISCONNECTED
        return Result.Success(Unit)
    }

    override suspend fun refreshAuthStatus(): NotionAuthStatus = _authStatus.value

    override suspend fun disconnect(): Result<Unit> {
        _authStatus.value = NotionAuthStatus.DISCONNECTED
        tokenToReturn = null
        return Result.Success(Unit)
    }

    override fun markAuthRequired() {
        _authStatus.value = NotionAuthStatus.AUTH_REQUIRED
    }
}

class FakeNotionClient : NotionClient {
    data class CreatedPageCall(
        val token: String,
        val targetId: String,
        val targetType: NotionTargetType,
        val title: String,
        val content: String?,
        val sourceMemoryId: String?
    )

    val createdPages = mutableListOf<CreatedPageCall>()
    var createPageFailure: Throwable? = null
    var searchTargetsResult: NotionSearchResponse = NotionSearchResponse(emptyList())
    var searchFailure: Throwable? = null

    override suspend fun searchTargets(
        accessToken: String,
        query: String?,
        startCursor: String?,
        pageSize: Int
    ): Result<NotionSearchResponse> {
        searchFailure?.let { return Result.Error(it) }
        return Result.Success(searchTargetsResult)
    }

    override suspend fun createPage(
        accessToken: String,
        targetId: String,
        targetType: NotionTargetType,
        title: String,
        content: String?,
        sourceMemoryId: String?
    ): Result<NotionCreatedPage> {
        createPageFailure?.let { return Result.Error(it) }
        createdPages.add(
            CreatedPageCall(
                token = accessToken,
                targetId = targetId,
                targetType = targetType,
                title = title,
                content = content,
                sourceMemoryId = sourceMemoryId
            )
        )
        return Result.Success(
            NotionCreatedPage(
                id = "page_${System.currentTimeMillis()}",
                url = "https://notion.so/page-id",
                title = title
            )
        )
    }
}
