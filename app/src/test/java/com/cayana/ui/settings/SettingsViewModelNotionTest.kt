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

        viewModel = SettingsViewModel(
            settingsRepository = repository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper,
            notionAuthManager = fakeAuthManager,
            notionClient = fakeNotionClient
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
    }
}
