package com.cayana.ui.settings

import android.content.Intent
import app.cash.turbine.test
import com.cayana.actions.tasks.auth.TasksAuthRequiredException
import com.cayana.actions.tasks.auth.TasksAuthStatus
import com.cayana.actions.tasks.auth.TasksAuthorizationManager
import com.cayana.actions.tasks.client.GoogleTaskItem
import com.cayana.actions.tasks.client.GoogleTaskList
import com.cayana.actions.tasks.client.GoogleTasksClient
import com.cayana.core.common.Result
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTasksTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var calendarProviderHelper: FakeCalendarProviderHelper
    private lateinit var fakeAuthManager: FakeTasksAuthManager
    private lateinit var fakeTasksClient: FakeTasksClient
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                isGoogleTasksConnected = false,
                googleTasksAuthStatus = TasksAuthStatus.DISCONNECTED,
                autoTasksEnabled = false
            )
        )
        permissionChecker = FakePermissionChecker()
        calendarProviderHelper = FakeCalendarProviderHelper()
        fakeAuthManager = FakeTasksAuthManager()
        fakeTasksClient = FakeTasksClient()

        viewModel = SettingsViewModel(
            settingsRepository = repository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper,
            tasksAuthManager = fakeAuthManager,
            tasksClient = fakeTasksClient
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
            assertFalse(state.settings.isGoogleTasksConnected)
            assertEquals(TasksAuthStatus.DISCONNECTED, state.settings.googleTasksAuthStatus)
            assertFalse(state.settings.autoTasksEnabled)
            assertNull(state.settings.selectedTaskListId)
        }
    }

    @Test
    fun connectTasksSuccessFlow() = runTest {
        var launched = false
        viewModel.startConnectGoogleTasks {
            launched = true
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // When launcher finishes with OK:
        viewModel.onGoogleTasksAuthorizationResult(true, Intent())
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.settings.isGoogleTasksConnected)
            assertEquals(TasksAuthStatus.CONNECTED, state.settings.googleTasksAuthStatus)
            assertEquals(2, state.availableTaskLists.size)
            assertEquals("list-1", state.settings.selectedTaskListId)
            assertEquals("工作待辦", state.settings.selectedTaskListTitle)
        }
    }

    @Test
    fun connectTasksDeniedFlow() = runTest {
        viewModel.onGoogleTasksAuthorizationResult(false, null)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.settings.isGoogleTasksConnected)
            assertEquals(TasksAuthStatus.DISCONNECTED, state.settings.googleTasksAuthStatus)
        }
    }

    @Test
    fun selectTaskListUpdatesSettings() = runTest {
        val targetList = GoogleTaskList(id = "list-2", title = "個人待辦")
        viewModel.selectTaskList(targetList)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("list-2", state.settings.selectedTaskListId)
            assertEquals("個人待辦", state.settings.selectedTaskListTitle)
        }
    }

    @Test
    fun toggleAutoTasksUpdatesSettings() = runTest {
        viewModel.toggleAutoTasks(true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.settings.autoTasksEnabled)
        }
    }

    @Test
    fun disconnectTasksClearsSettingsAndState() = runTest {
        // Pre-configure as connected
        repository.updateGoogleTasksAuthStatus(TasksAuthStatus.CONNECTED)
        repository.updateAutoTasksEnabled(true)
        repository.updateSelectedTaskList("list-1", "工作待辦")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.disconnectGoogleTasks()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.settings.isGoogleTasksConnected)
            assertEquals(TasksAuthStatus.DISCONNECTED, state.settings.googleTasksAuthStatus)
            assertFalse(state.settings.autoTasksEnabled)
            assertNull(state.settings.selectedTaskListId)
            assertNull(state.settings.selectedTaskListTitle)
            assertTrue(state.availableTaskLists.isEmpty())
        }
    }

    @Test
    fun authStatusInconsistencyReconciledOnStartup() = runTest {
        // Suppose persistent storage claims CONNECTED, but token expired / external revoke happened
        repository.updateGoogleTasksAuthStatus(TasksAuthStatus.CONNECTED)
        fakeAuthManager.statusToReturnOnRefresh = TasksAuthStatus.AUTH_REQUIRED

        viewModel.checkGoogleTasksAuthStatus()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(TasksAuthStatus.AUTH_REQUIRED, state.settings.googleTasksAuthStatus)
            assertFalse(state.settings.isGoogleTasksConnected)
        }
    }

    @Test
    fun disconnectRevokeFailureHandled() = runTest {
        repository.updateGoogleTasksAuthStatus(TasksAuthStatus.CONNECTED)
        repository.updateAutoTasksEnabled(true)
        repository.updateSelectedTaskList("list-1", "工作待辦")
        testDispatcher.scheduler.advanceUntilIdle()

        fakeAuthManager.shouldRevokeFail = true
        viewModel.disconnectGoogleTasks()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse("Local transmission must immediately stop", state.settings.isGoogleTasksConnected)
            assertEquals(TasksAuthStatus.DISCONNECTED, state.settings.googleTasksAuthStatus)
            assertFalse(state.settings.autoTasksEnabled)
            assertNull(state.settings.selectedTaskListId)
            assertNotNull("UI must reflect remote revoke failure", state.tasksError)
            assertTrue(state.tasksError!!.contains("遠端授權撤銷未完成"))
        }
    }

    private class FakeTasksAuthManager : TasksAuthorizationManager {
        private val _authStatus = MutableStateFlow(TasksAuthStatus.DISCONNECTED)
        override val authStatus: StateFlow<TasksAuthStatus> = _authStatus.asStateFlow()
        var statusToReturnOnRefresh: TasksAuthStatus = TasksAuthStatus.CONNECTED
        var shouldRevokeFail: Boolean = false

        override suspend fun getAccessToken(): Result<String> {
            return if (_authStatus.value == TasksAuthStatus.CONNECTED) {
                Result.Success("valid-token")
            } else {
                Result.Error(TasksAuthRequiredException())
            }
        }

        override suspend fun getAuthorizationIntentSender(): Result<android.content.IntentSender?> = Result.Success(null)

        override suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> {
            _authStatus.value = TasksAuthStatus.CONNECTED
            return Result.Success(Unit)
        }

        override suspend fun invalidateCachedAccessToken() {
            _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
        }

        override suspend fun disconnect(): Result<Unit> {
            _authStatus.value = TasksAuthStatus.DISCONNECTED
            if (shouldRevokeFail) {
                return Result.Error(java.io.IOException("Remote revoke failed: 500"))
            }
            return Result.Success(Unit)
        }

        override suspend fun refreshAuthStatus(): TasksAuthStatus {
            _authStatus.value = statusToReturnOnRefresh
            return statusToReturnOnRefresh
        }

        override fun markAuthRequired() {
            _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
        }
    }

    private class FakeTasksClient : GoogleTasksClient {
        val lists = listOf(
            GoogleTaskList(id = "list-1", title = "工作待辦"),
            GoogleTaskList(id = "list-2", title = "個人待辦")
        )

        override suspend fun getTaskLists(accessToken: String): Result<List<GoogleTaskList>> {
            return Result.Success(lists)
        }

        override suspend fun createTask(
            accessToken: String,
            taskListId: String,
            title: String,
            notes: String?,
            dueRfc3339: String?
        ): Result<GoogleTaskItem> {
            return Result.Success(GoogleTaskItem(id = "new-task-1", title = title))
        }

        override suspend fun findTaskByMemoryRef(
            accessToken: String,
            taskListId: String,
            sourceMemoryId: String,
            title: String
        ): Result<GoogleTaskItem?> {
            return Result.Success(null)
        }
    }
}
