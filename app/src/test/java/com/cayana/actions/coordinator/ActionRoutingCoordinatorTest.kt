package com.cayana.actions.coordinator

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.actions.classifier.DeterministicActionClassifier
import com.cayana.actions.destination.CalendarDestination
import com.cayana.actions.destination.DefaultDestinationRegistry
import com.cayana.actions.destination.NoteDestination
import com.cayana.actions.destination.UnconfiguredNoteDestination
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.DestinationType
import com.cayana.actions.router.DefaultActionRouter
import com.cayana.actions.router.DefaultActionRoutingHistoryTracker
import com.cayana.actions.tasks.auth.TasksAuthStatus
import com.cayana.actions.tasks.client.GoogleTaskItem
import com.cayana.actions.tasks.client.GoogleTaskList
import com.cayana.actions.tasks.client.GoogleTasksClient
import com.cayana.actions.tasks.data.TaskActionDao
import com.cayana.actions.tasks.destination.GoogleTasksDestination
import com.cayana.actions.tasks.receiver.ConfirmTaskActionReceiver
import com.cayana.core.common.Result
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.event.extractor.DeterministicEventExtractor
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionRoutingCoordinatorTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var taskActionDao: TaskActionDao
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var fakeAuthManager: FakeTasksAuthManager
    private lateinit var fakeTasksClient: FakeTasksClient
    private lateinit var googleTasksDestination: GoogleTasksDestination
    private lateinit var actionRouter: DefaultActionRouter
    private lateinit var coordinator: ActionRoutingCoordinator

    private val zoneId = ZoneId.of("Asia/Taipei")
    private val fixedRefTime = ZonedDateTime.of(
        LocalDate.of(2026, 10, 5),
        LocalTime.of(10, 0),
        zoneId
    ).toInstant()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskActionDao = database.taskActionDao()
        memoryRepository = FakeMemoryRepository()

        fakeAuthManager = FakeTasksAuthManager(initialStatus = TasksAuthStatus.CONNECTED)
        fakeTasksClient = FakeTasksClient()

        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                isGoogleTasksConnected = true,
                googleTasksAuthStatus = TasksAuthStatus.CONNECTED,
                autoTasksEnabled = true,
                selectedTaskListId = "list-test-1",
                selectedTaskListTitle = "測試清單"
            )
        )

        val logger = DefaultCayanaLogger()
        googleTasksDestination = GoogleTasksDestination(
            taskActionDao = taskActionDao,
            tasksAuthManager = fakeAuthManager,
            tasksClient = fakeTasksClient,
            settingsRepository = settingsRepository,
            logger = logger
        )

        val destinationRegistry = DefaultDestinationRegistry(
            destinations = listOf(
                FakeCalendarDestination(),
                googleTasksDestination,
                UnconfiguredNoteDestination()
            )
        )

        val classifier = DeterministicActionClassifier(
            eventExtractor = DeterministicEventExtractor()
        )

        val routingHistoryTracker = DefaultActionRoutingHistoryTracker(
            calendarActionDao = database.calendarActionDao(),
            taskActionDao = taskActionDao
        )

        actionRouter = DefaultActionRouter(
            classifier = classifier,
            destinationRegistry = destinationRegistry,
            routingHistoryTracker = routingHistoryTracker,
            memoryRepository = memoryRepository,
            logger = logger
        )

        coordinator = ActionRoutingCoordinator(
            context = context,
            actionRouter = actionRouter,
            taskActionDao = taskActionDao,
            googleTasksDestination = googleTasksDestination,
            settingsRepository = settingsRepository,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun highConfidenceTaskAutoCreatedAndPersisted() = runTest {
        val memory = MemoryItem(
            id = "mem-high-task-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 繳交成果報告 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(memory)

        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertTrue("High confidence task must be automatically handled", handled)

        // Verify remote task was created
        assertEquals(1, fakeTasksClient.createdTasks.size)
        val created = fakeTasksClient.createdTasks.first()
        assertTrue(created.title.contains("繳交成果報告"))

        // Verify Room persistence status
        val action = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-test-1")
        assertNotNull(action)
        assertEquals("CREATED", action!!.status)
        assertEquals(created.id, action.destinationTaskId)
    }

    @Test
    fun lowConfidenceTaskRequiresConfirmationZeroRemoteUpload() = runTest {
        // Text contains a vague task without clear due date or explicit TODO marker -> confidence 0.5 < 0.8
        val memory = MemoryItem(
            id = "mem-low-task-1",
            sourceType = SourceType.SCREENSHOT,
            title = "可能待辦",
            rawText = "可能要記得買咖啡豆"
        )
        memoryRepository.saveMemory(memory)

        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertTrue("Confirmation notification must be posted", handled)

        // Remote tasks must NOT receive any upload before confirmation!
        assertEquals("Unconfirmed task must have zero remote transmission", 0, fakeTasksClient.createdTasks.size)

        // Room action must be recorded as CONFIRM_PENDING
        val action = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-test-1")
        assertNotNull(action)
        assertEquals("CONFIRM_PENDING", action!!.status)
    }

    @Test
    fun userConfirmsTaskViaReceiverCreatesTaskRemotely() = runTest {
        val memory = MemoryItem(
            id = "mem-confirm-task-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "可能要記得買牛奶"
        )
        memoryRepository.saveMemory(memory)

        // 1. Process generates CONFIRM_PENDING
        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        val action = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-test-1")
        assertNotNull(action)
        assertEquals("CONFIRM_PENDING", action!!.status)
        assertEquals(0, fakeTasksClient.createdTasks.size)

        // 2. User clicks "Confirm" in notification -> receiver triggered
        val receiver = ConfirmTaskActionReceiver(
            injectedDestination = googleTasksDestination,
            injectedDao = taskActionDao
        )
        val intent = Intent().apply {
            putExtra(ConfirmTaskActionReceiver.EXTRA_DECISION, ConfirmTaskActionReceiver.DECISION_CONFIRM)
            putExtra(ConfirmTaskActionReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(ConfirmTaskActionReceiver.EXTRA_NOTIFICATION_ID, 12345)
        }
        receiver.handleIntent(context, intent)

        // Verify task is now remotely created
        assertEquals(1, fakeTasksClient.createdTasks.size)
        val updatedAction = taskActionDao.getActionById(action.id)
        assertEquals("CREATED", updatedAction?.status)
        assertEquals(fakeTasksClient.createdTasks.first().id, updatedAction?.destinationTaskId)
    }

    @Test
    fun userIgnoresTaskViaReceiverMarksIgnoredZeroTransmission() = runTest {
        val memory = MemoryItem(
            id = "mem-ignore-task-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "可能要記得整理桌面"
        )
        memoryRepository.saveMemory(memory)

        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        val action = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-test-1")
        assertNotNull(action)

        val receiver = ConfirmTaskActionReceiver(
            injectedDestination = googleTasksDestination,
            injectedDao = taskActionDao
        )
        val intent = Intent().apply {
            putExtra(ConfirmTaskActionReceiver.EXTRA_DECISION, ConfirmTaskActionReceiver.DECISION_IGNORE)
            putExtra(ConfirmTaskActionReceiver.EXTRA_ACTION_ID, action!!.id)
            putExtra(ConfirmTaskActionReceiver.EXTRA_NOTIFICATION_ID, 12345)
        }
        receiver.handleIntent(context, intent)

        assertEquals("Ignored task must never upload", 0, fakeTasksClient.createdTasks.size)
        val updatedAction = taskActionDao.getActionById(action!!.id)
        assertEquals("IGNORED", updatedAction?.status)
    }

    @Test
    fun autoTasksDisabledZeroTransmission() = runTest {
        settingsRepository.updateAutoTasksEnabled(false)

        val memory = MemoryItem(
            id = "mem-auto-disabled-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 繳交成果報告 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(memory)

        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse("Disabled auto tasks must not trigger auto-creation", handled)
        assertEquals(0, fakeTasksClient.createdTasks.size)
    }

    @Test
    fun unauthorizedTasksZeroTransmission() = runTest {
        fakeAuthManager.setStatus(TasksAuthStatus.DISCONNECTED)
        settingsRepository.updateGoogleTasksAuthStatus(TasksAuthStatus.DISCONNECTED)

        val memory = MemoryItem(
            id = "mem-unauth-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 繳交成果報告 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(memory)

        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertEquals("Disconnected tasks provider must have 0 remote requests", 0, fakeTasksClient.createdTasks.size)
    }

    @Test
    fun calendarEventSkippedByActionRoutingCoordinator() = runTest {
        // Plain calendar event without task keywords
        val memory = MemoryItem(
            id = "mem-cal-event-1",
            sourceType = SourceType.SCREENSHOT,
            title = "行程",
            rawText = "周五下午兩點專案評審會議 地點: 402會議室"
        )
        memoryRepository.saveMemory(memory)

        // ActionRoutingCoordinator must not process Calendar events (handled separately by CalendarProcessingCoordinator)
        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse("Calendar events must be untouched by ActionRoutingCoordinator", handled)
        assertEquals(0, fakeTasksClient.createdTasks.size)
    }

    @Test
    fun destinationFailureNeverRollsBackCanonicalMemory() = runTest {
        fakeTasksClient.shouldFail = true

        val memory = MemoryItem(
            id = "mem-dest-fail-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 繳交成果報告 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(memory)

        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)

        // Memory in MemoryRepository must remain strictly preserved!
        val preserved = memoryRepository.getMemoryById(memory.id).first()
        assertNotNull("Canonical memory must remain intact after destination error", preserved)
        assertEquals(memory.id, preserved?.id)
    }

    private class FakeCalendarDestination : CalendarDestination {
        override val destinationType: DestinationType = DestinationType.CALENDAR
        override suspend fun isConfigured(): Boolean = true
        override suspend fun isAuthorized(): Boolean = true
        override suspend fun createCalendarEvent(
            candidate: ActionCandidate,
            memoryItem: MemoryItem
        ): DestinationExecutionResult {
            return DestinationExecutionResult.Success("cal-event-1", "Handled")
        }
    }

    private class FakeTasksAuthManager(
        initialStatus: TasksAuthStatus = TasksAuthStatus.CONNECTED
    ) : com.cayana.actions.tasks.auth.TasksAuthorizationManager {
        private val _authStatus = MutableStateFlow(initialStatus)
        override val authStatus: StateFlow<TasksAuthStatus> = _authStatus.asStateFlow()

        fun setStatus(status: TasksAuthStatus) {
            _authStatus.value = status
        }

        override suspend fun getAccessToken(): Result<String> {
            return if (_authStatus.value == TasksAuthStatus.CONNECTED) {
                Result.Success("valid-test-token")
            } else {
                Result.Error(com.cayana.actions.tasks.auth.TasksAuthRequiredException())
            }
        }

        override suspend fun getAuthorizationIntentSender(): Result<android.content.IntentSender?> = Result.Success(null)
        override suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> = Result.Success(Unit)
        override suspend fun invalidateCachedAccessToken() {
            _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
        }
        override suspend fun disconnect(): Result<Unit> {
            _authStatus.value = TasksAuthStatus.DISCONNECTED
            return Result.Success(Unit)
        }
        override suspend fun refreshAuthStatus(): TasksAuthStatus = _authStatus.value
        override fun markAuthRequired() {
            _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
        }
    }

    private class FakeTasksClient : GoogleTasksClient {
        val createdTasks = mutableListOf<GoogleTaskItem>()
        var shouldFail: Boolean = false

        override suspend fun getTaskLists(accessToken: String): Result<List<GoogleTaskList>> {
            return Result.Success(listOf(GoogleTaskList(id = "list-test-1", title = "測試清單")))
        }

        override suspend fun createTask(
            accessToken: String,
            taskListId: String,
            title: String,
            notes: String?,
            dueRfc3339: String?
        ): Result<GoogleTaskItem> {
            if (shouldFail) {
                return Result.Error(RuntimeException("Simulated Google API failure"))
            }
            val item = GoogleTaskItem(id = "task-${createdTasks.size + 1}", title = title, notes = notes, due = dueRfc3339)
            createdTasks.add(item)
            return Result.Success(item)
        }

        override suspend fun findTaskByMemoryRef(
            accessToken: String,
            taskListId: String,
            sourceMemoryId: String,
            title: String
        ): Result<GoogleTaskItem?> {
            return Result.Success(createdTasks.find { it.notes?.contains(sourceMemoryId) == true })
        }
    }
}
