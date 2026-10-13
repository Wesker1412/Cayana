package com.cayana.ui.settings

import android.net.Uri
import app.cash.turbine.test
import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.actions.notion.auth.NotionAuthStatus
import com.cayana.actions.notion.auth.NotionAuthorizationManager
import com.cayana.actions.notion.client.NotionClient
import com.cayana.actions.notion.client.NotionCreatedPage
import com.cayana.actions.notion.client.NotionSearchResponse
import com.cayana.actions.notion.client.NotionTarget
import com.cayana.actions.notion.client.NotionTargetType
import com.cayana.core.common.Result
import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.NotionTransmissionPolicy
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import com.cayana.actions.notion.auth.FakeCayanaLogger
import com.cayana.actions.notion.data.NotionActionEntity
import com.cayana.actions.notion.destination.FakeNotionActionDao
import com.cayana.actions.notion.destination.NotionDestination
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelNotionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var calendarProviderHelper: FakeCalendarProviderHelper
    private lateinit var fakeAuthManager: FakeNotionAuthManager
    private lateinit var fakeNotionClient: FakeNotionClient
    private lateinit var fakeDao: FakeNotionActionDao
    private lateinit var fakeDestination: NotionDestination
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                notionAuthStatus = NotionAuthStatus.DISCONNECTED,
                autoNotionEnabled = false
            )
        )
        permissionChecker = FakePermissionChecker()
        calendarProviderHelper = FakeCalendarProviderHelper()
        fakeAuthManager = FakeNotionAuthManager()
        fakeNotionClient = FakeNotionClient()
        fakeDao = FakeNotionActionDao()
        fakeDestination = NotionDestination(
            notionActionDao = fakeDao,
            notionAuthManager = fakeAuthManager,
            notionClient = fakeNotionClient,
            settingsRepository = repository,
            logger = FakeCayanaLogger(),
            ioDispatcher = testDispatcher
        )

        viewModel = SettingsViewModel(
            settingsRepository = repository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper,
            notionAuthManager = fakeAuthManager,
            notionClient = fakeNotionClient,
            notionActionDao = fakeDao,
            notionDestination = fakeDestination
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun defaultSettingsAreOff() = runTest {
        viewModel.uiState.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = awaitItem()
            assertFalse(state.settings.isNotionConnected)
            assertEquals(NotionAuthStatus.DISCONNECTED, state.settings.notionAuthStatus)
            assertEquals(NotionTransmissionPolicy.OFF, state.settings.notionTransmissionPolicy)
            assertFalse(state.settings.autoNotionEnabled)
            assertNull(state.settings.selectedNotionTargetId)
            assertNull(state.settings.selectedNotionTargetTitle)
            assertNull(state.settings.selectedNotionTargetType)
        }
    }

    @Test
    fun startConnectNotionOpensBrowserUrl() = runTest {
        var launchedUri: Uri? = null
        viewModel.startConnectNotion { uri ->
            launchedUri = uri
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(launchedUri)
        assertEquals("https://api.notion.com/v1/oauth/authorize?test=1", launchedUri.toString())
    }

    @Test
    fun startConnectNotionFailureSetsError() = runTest {
        fakeAuthManager.getAuthUrlResult = Result.Error(IOException("Network error"))

        var launchedUri: Uri? = null
        viewModel.startConnectNotion { uri ->
            launchedUri = uri
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(launchedUri)
        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.notionError)
            assertTrue(state.notionError!!.contains("無法取得 Notion 授權網址"))
        }
    }

    @Test
    fun loadNotionTargetsSuccessDoesNotAutoSelectFirstTarget() = runTest {
        fakeAuthManager.setStatus(NotionAuthStatus.CONNECTED)

        viewModel.loadNotionTargets(query = "Notes")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Notes", fakeNotionClient.lastSearchQuery)
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(3, state.availableNotionTargets.size)
            assertTrue(state.notionTargetHasMore)
            assertEquals("cursor-next", state.notionTargetNextCursor)
            assertFalse(state.isLoadingNotionTargets)
            assertNull(state.notionError)

            // Stage 9C-3 requirement: Must NOT auto-select first target; require explicit user selection
            assertNull(state.settings.selectedNotionTargetId)
            assertNull(state.settings.selectedNotionTargetTitle)
        }
    }

    @Test
    fun loadNotionTargetsPaginationLoadMore() = runTest {
        fakeAuthManager.setStatus(NotionAuthStatus.CONNECTED)

        // Initial load
        viewModel.loadNotionTargets()
        testDispatcher.scheduler.advanceUntilIdle()

        // Prepare page 2 response
        fakeNotionClient.searchResponse = Result.Success(
            NotionSearchResponse(
                targets = listOf(
                    NotionTarget(id = "page-99", title = "Archive Notes", type = NotionTargetType.PAGE, isSelectable = true)
                ),
                nextCursor = null,
                hasMore = false
            )
        )

        // Load more
        viewModel.loadNotionTargets(loadMore = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("cursor-next", fakeNotionClient.lastStartCursor)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(4, state.availableNotionTargets.size)
            assertFalse(state.notionTargetHasMore)
            assertNull(state.notionTargetNextCursor)
            assertEquals("page-99", state.availableNotionTargets.last().id)
        }
    }

    @Test
    fun loadNotionTargetsExpiredAuthTransitionsToAuthRequired() = runTest {
        fakeAuthManager.setStatus(NotionAuthStatus.CONNECTED)
        fakeNotionClient.searchResponse = Result.Error(NotionAuthRequiredException("401 Unauthorized"))

        viewModel.loadNotionTargets()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(NotionAuthStatus.AUTH_REQUIRED, state.settings.notionAuthStatus)
            assertFalse(state.settings.isNotionConnected)
            assertNotNull(state.notionError)
            assertTrue(state.notionError!!.contains("Notion 授權已失效"))
        }
    }

    @Test
    fun selectNotionTargetUpdatesSettingsWhenSelectable() = runTest {
        val target = NotionTarget(
            id = "ds-1",
            title = "Items Table",
            type = NotionTargetType.DATA_SOURCE,
            isSelectable = true
        )
        viewModel.selectNotionTarget(target)
        testDispatcher.scheduler.advanceUntilIdle()

        val settings = repository.getSettings().first()
        assertEquals("ds-1", settings.selectedNotionTargetId)
        assertEquals("Items Table", settings.selectedNotionTargetTitle)
        assertEquals("data_source", settings.selectedNotionTargetType)
    }

    @Test
    fun selectNotionTargetRejectsUnselectableTarget() = runTest {
        val target = NotionTarget(
            id = "db-1",
            title = "Project Tasks",
            type = NotionTargetType.DATABASE,
            isSelectable = false
        )
        viewModel.selectNotionTarget(target)
        testDispatcher.scheduler.advanceUntilIdle()

        val settings = repository.getSettings().first()
        assertNull(settings.selectedNotionTargetId)
        assertNotNull(viewModel.uiState.value.notionError)
        assertTrue(viewModel.uiState.value.notionError!!.contains("此資料庫無法直接寫入"))
    }

    @Test
    fun setNotionTransmissionPolicyAutoRequiresTargetFirst() = runTest {
        // Without target, AUTO_FOR_SELECTED_TYPES is rejected
        viewModel.setNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(NotionTransmissionPolicy.OFF, repository.getSettings().first().notionTransmissionPolicy)
        assertNotNull(viewModel.uiState.value.notionError)

        // Select valid target, then set AUTO_FOR_SELECTED_TYPES
        repository.updateSelectedNotionTarget("page-1", "My Page", "page")
        viewModel.setNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES, repository.getSettings().first().notionTransmissionPolicy)
        assertTrue(repository.getSettings().first().autoNotionEnabled)
    }

    @Test
    fun toggleAutoNotionSourceUpdatesAllowedSources() = runTest {
        viewModel.toggleAutoNotionSource(SourceType.PHOTO, true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.getSettings().first().autoNotionSources.contains(SourceType.PHOTO))

        viewModel.toggleAutoNotionSource(SourceType.PHOTO, false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(repository.getSettings().first().autoNotionSources.contains(SourceType.PHOTO))
    }

    @Test
    fun disconnectNotionClearsSettingsAndState() = runTest {
        // Pre-configure as connected with active targets
        repository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        repository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        repository.updateSelectedNotionTarget("page-1", "Work Notes", "page")
        fakeAuthManager.setStatus(NotionAuthStatus.CONNECTED)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.disconnectNotion()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(fakeAuthManager.disconnectCalled)

        val settings = repository.getSettings().first()
        assertFalse(settings.isNotionConnected)
        assertEquals(NotionAuthStatus.DISCONNECTED, settings.notionAuthStatus)
        assertEquals(NotionTransmissionPolicy.OFF, settings.notionTransmissionPolicy)
        assertFalse(settings.autoNotionEnabled)
        assertNull(settings.selectedNotionTargetId)
        assertNull(settings.selectedNotionTargetTitle)
        assertNull(settings.selectedNotionTargetType)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.availableNotionTargets.isEmpty())
            assertFalse(state.notionTargetHasMore)
            assertNull(state.notionTargetNextCursor)
        }
    }

    @Test
    fun authStatusInconsistencyReconciledOnStartup() = runTest {
        repository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        fakeAuthManager.statusToReturnOnRefresh = NotionAuthStatus.AUTH_REQUIRED

        viewModel.checkNotionAuthStatus()
        testDispatcher.scheduler.advanceUntilIdle()

        val settings = repository.getSettings().first()
        assertEquals(NotionAuthStatus.AUTH_REQUIRED, settings.notionAuthStatus)
        assertFalse(settings.isNotionConnected)
    }

    @Test
    fun loadNotionAttentionActionsDisplaysInUiState() = runTest {
        val action = NotionActionEntity(
            id = "attn-1",
            sourceMemoryId = "mem-1",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "page-1",
            targetType = "PAGE",
            status = "NEEDS_ATTENTION",
            title = "Attention Required Note",
            lastError = "連線異常或伺服器回應未確認"
        )
        fakeDao.insert(action)

        viewModel.loadNotionAttentionActions()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.notionAttentionActions.size)
            assertEquals("attn-1", state.notionAttentionActions[0].id)
            assertEquals("Attention Required Note", state.notionAttentionActions[0].title)
        }
    }

    @Test
    fun ignoreNotionAttentionActionMarksIgnoredAndRefreshes() = runTest {
        val action = NotionActionEntity(
            id = "attn-2",
            sourceMemoryId = "mem-2",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "page-1",
            targetType = "PAGE",
            status = "NEEDS_ATTENTION",
            title = "Attention To Ignore",
            lastError = "連線異常"
        )
        fakeDao.insert(action)
        viewModel.loadNotionAttentionActions()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.notionAttentionActions.size)

        viewModel.ignoreNotionAttentionAction("attn-2")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("IGNORED", fakeDao.getActionById("attn-2")!!.status)
        assertEquals(0, viewModel.uiState.value.notionAttentionActions.size)
    }

    @Test
    fun confirmNotionAttentionActionExecutesAndRefreshes() = runTest {
        repository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        repository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        repository.updateSelectedNotionTarget("page-1", "Work Notes", "page")
        fakeAuthManager.setStatus(NotionAuthStatus.CONNECTED)
        fakeAuthManager.tokenResult = Result.Success("valid-token")

        val action = NotionActionEntity(
            id = "attn-3",
            sourceMemoryId = "mem-3",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "page-1",
            targetType = "PAGE",
            status = "CONFIRM_PENDING",
            title = "Attention To Confirm"
        )
        fakeDao.insert(action)
        viewModel.loadNotionAttentionActions()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmNotionAttentionAction("attn-3")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("CREATED", fakeDao.getActionById("attn-3")!!.status)
        assertEquals(0, viewModel.uiState.value.notionAttentionActions.size)
    }

    @Test
    fun autoAskAutoResetsEligibilityEpoch() = runTest {
        repository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        testDispatcher.scheduler.advanceUntilIdle()
        val settingsAuto1 = repository.getSettings().first()
        assertTrue(settingsAuto1.autoNotionEligibilityEpoch > 0L)
        val epoch1 = settingsAuto1.autoNotionEligibilityEpoch

        repository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        testDispatcher.scheduler.advanceUntilIdle()
        val settingsAsk = repository.getSettings().first()
        assertEquals(0L, settingsAsk.autoNotionEligibilityEpoch)

        repository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        testDispatcher.scheduler.advanceUntilIdle()
        val settingsAuto2 = repository.getSettings().first()
        assertTrue(settingsAuto2.autoNotionEligibilityEpoch > 0L)
        assertTrue(settingsAuto2.autoNotionEligibilityEpoch >= epoch1)
    }

    @Test
    fun attentionConfirmationShowsPayloadAndTarget() = runTest {
        val payload = "Detailed note content payload within 2000 character limits for inspection."
        val targetName = "Target Database Wiki"
        val action = NotionActionEntity(
            id = "attn-target-payload",
            sourceMemoryId = "mem-payload",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "target-wiki-1",
            targetTitle = targetName,
            targetType = "PAGE",
            destinationPageId = null,
            status = "NEEDS_ATTENTION",
            title = "Attention Payload Note",
            content = payload,
            lastError = "連線異常或伺服器回應未確認",
            attemptCount = 1
        )
        fakeDao.insert(action)

        viewModel.loadNotionAttentionActions()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.notionAttentionActions.size)
            val item = state.notionAttentionActions[0]
            assertEquals(targetName, item.targetTitle)
            assertEquals(payload, item.content)
            assertEquals("NEEDS_ATTENTION", item.status)
        }

        fakeNotionClient.searchResponse = Result.Success(NotionSearchResponse(emptyList()))
        viewModel.confirmNotionAttentionAction("attn-target-payload")
        testDispatcher.scheduler.advanceUntilIdle()

        val reconciledAction = fakeDao.getActionById("attn-target-payload")
        assertEquals("NEEDS_ATTENTION", reconciledAction!!.status)
        assertEquals(1, reconciledAction.attemptCount)
    }

    private class FakeNotionAuthManager : NotionAuthorizationManager {
        private val _authStatus = MutableStateFlow(NotionAuthStatus.DISCONNECTED)
        override val authStatus: StateFlow<NotionAuthStatus> = _authStatus.asStateFlow()
        var statusToReturnOnRefresh: NotionAuthStatus = NotionAuthStatus.CONNECTED
        var getAuthUrlResult: Result<String> = Result.Success("https://api.notion.com/v1/oauth/authorize?test=1")
        var tokenResult: Result<String> = Result.Success("fake-notion-token")
        var disconnectCalled = false

        fun setStatus(status: NotionAuthStatus) {
            _authStatus.value = status
        }

        override suspend fun getAuthorizationUrl(redirectUri: String?): Result<String> = getAuthUrlResult

        override suspend fun handleAuthorizationCallback(ticket: String, state: String, redirectUri: String?): Result<Unit> {
            _authStatus.value = NotionAuthStatus.CONNECTED
            return Result.Success(Unit)
        }

        override suspend fun handleAuthorizationFailure(error: String): Result<Unit> {
            _authStatus.value = NotionAuthStatus.DISCONNECTED
            return Result.Success(Unit)
        }

        override fun markAuthRequired() {
            _authStatus.value = NotionAuthStatus.AUTH_REQUIRED
        }

        override suspend fun getAccessToken(): Result<String> = tokenResult

        override suspend fun refreshAuthStatus(): NotionAuthStatus {
            _authStatus.value = statusToReturnOnRefresh
            return statusToReturnOnRefresh
        }

        override suspend fun disconnect(): Result<Unit> {
            disconnectCalled = true
            _authStatus.value = NotionAuthStatus.DISCONNECTED
            return Result.Success(Unit)
        }
    }

    private class FakeNotionClient : NotionClient {
        var searchResponse: Result<NotionSearchResponse> = Result.Success(
            NotionSearchResponse(
                targets = listOf(
                    NotionTarget(id = "page-1", title = "Work Notes", type = NotionTargetType.PAGE, isSelectable = true),
                    NotionTarget(id = "db-1", title = "Project Tasks", type = NotionTargetType.DATABASE, isSelectable = false),
                    NotionTarget(id = "ds-1", title = "Items Table", type = NotionTargetType.DATA_SOURCE, isSelectable = true)
                ),
                nextCursor = "cursor-next",
                hasMore = true
            )
        )

        var lastSearchQuery: String? = null
        var lastStartCursor: String? = null
        var lastPageSize: Int? = null

        override suspend fun searchTargets(
            accessToken: String,
            query: String?,
            startCursor: String?,
            pageSize: Int
        ): Result<NotionSearchResponse> {
            lastSearchQuery = query
            lastStartCursor = startCursor
            lastPageSize = pageSize
            return searchResponse
        }

        override suspend fun createPage(
            accessToken: String,
            targetId: String,
            targetType: NotionTargetType,
            title: String,
            content: String?,
            sourceMemoryId: String?
        ): Result<NotionCreatedPage> {
            return Result.Success(NotionCreatedPage("p-1", "https://notion.so/p-1", title))
        }

        override suspend fun getDataSourceTitleProperty(
            accessToken: String,
            dataSourceId: String
        ): Result<String> {
            return Result.Success("Name")
        }

        override suspend fun verifyPageReference(
            accessToken: String,
            pageId: String,
            expectedTargetId: String,
            expectedSourceMemoryId: String
        ): Result<Boolean> {
            return Result.Success(true)
        }
    }
}
