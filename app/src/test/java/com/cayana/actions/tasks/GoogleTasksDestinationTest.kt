package com.cayana.actions.tasks

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.actions.destination.DestinationRegistry
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.DestinationType
import com.cayana.actions.model.RoutingDecision
import com.cayana.actions.router.DefaultActionRouter
import com.cayana.actions.router.DefaultActionRoutingHistoryTracker
import com.cayana.actions.tasks.auth.TasksAuthRequiredException
import com.cayana.actions.tasks.auth.TasksAuthStatus
import com.cayana.actions.tasks.auth.TasksAuthorizationManager
import com.cayana.actions.tasks.client.GoogleTaskItem
import com.cayana.actions.tasks.client.GoogleTaskList
import com.cayana.actions.tasks.client.GoogleTasksClient
import com.cayana.actions.tasks.client.TasksHttpException
import com.cayana.actions.tasks.data.TaskActionDao
import com.cayana.actions.tasks.data.TaskActionEntity
import com.cayana.actions.tasks.destination.GoogleTasksDestination
import com.cayana.core.common.Result
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.event.extractor.DeterministicEventExtractor
import com.cayana.actions.classifier.DeterministicActionClassifier
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
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleTasksDestinationTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var taskActionDao: TaskActionDao
    private lateinit var fakeAuthManager: FakeTasksAuthorizationManager
    private lateinit var fakeTasksClient: FakeGoogleTasksClient
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var destination: GoogleTasksDestination
    private lateinit var memoryRepository: FakeMemoryRepository

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

        fakeAuthManager = FakeTasksAuthorizationManager(
            initialStatus = TasksAuthStatus.CONNECTED,
            tokenToReturn = "mock-valid-google-tasks-token"
        )
        fakeTasksClient = FakeGoogleTasksClient()

        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                isGoogleTasksConnected = true,
                googleTasksAuthStatus = TasksAuthStatus.CONNECTED,
                autoTasksEnabled = true,
                selectedTaskListId = "list-personal-1",
                selectedTaskListTitle = "個人待辦"
            )
        )

        memoryRepository = FakeMemoryRepository()

        destination = GoogleTasksDestination(
            taskActionDao = taskActionDao,
            tasksAuthManager = fakeAuthManager,
            tasksClient = fakeTasksClient,
            settingsRepository = settingsRepository,
            logger = DefaultCayanaLogger()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createTestTaskCandidate(
        sourceMemoryId: String = UUID.randomUUID().toString(),
        title: String = "繳納水費",
        dueAt: ZonedDateTime? = ZonedDateTime.of(LocalDate.of(2026, 10, 25), LocalTime.of(18, 30), zoneId)
    ): ActionCandidate {
        return ActionCandidate(
            type = ActionCandidateType.TASK,
            title = title,
            sourceMemoryId = sourceMemoryId,
            confidence = 0.90f,
            dueAt = dueAt?.toInstant(),
            notes = "本月份水費共 350 元"
        )
    }

    // 1. Create task success
    @Test
    fun createTaskSuccess() = runTest {
        val candidate = createTestTaskCandidate()
        val result = destination.createTaskInternal(candidate, zoneId)

        assertTrue("Expected Success result", result is DestinationExecutionResult.Success)
        val success = result as DestinationExecutionResult.Success
        assertNotNull("Destination reference ID should be returned", success.destinationReferenceId)
        assertTrue("Message should explain date limitation", success.message.contains("Google Tasks 官方限制僅保留截止日期"))

        // Verify Room persistence
        val action = taskActionDao.getAction(candidate.sourceMemoryId, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-personal-1")
        assertNotNull("Task action record should be persisted in Room", action)
        assertEquals("CREATED", action!!.status)
        assertEquals(success.destinationReferenceId, action.destinationTaskId)
        assertEquals(candidate.dueAt?.toEpochMilli(), action.originalDueAt)
        assertEquals("2026-10-25T00:00:00.000Z", action.googleDue)

        // Verify HTTP call details
        assertEquals(1, fakeTasksClient.postRequests.size)
        val req = fakeTasksClient.postRequests[0]
        assertEquals("list-personal-1", req.taskListId)
        assertEquals("繳納水費", req.title)
        assertEquals("2026-10-25T00:00:00.000Z", req.dueRfc3339)
        assertTrue("Notes should contain exact due time reminder disclosure", req.notes?.contains("提醒時間: 2026-10-25 18:30") == true)
        assertTrue("Notes should contain source memory reference", req.notes?.contains("cayana://memory/${candidate.sourceMemoryId}") == true)
    }

    // 2. Unauthorized -> zero transmission
    @Test
    fun unauthorizedZeroTransmission() = runTest {
        fakeAuthManager.setStatus(TasksAuthStatus.AUTH_REQUIRED)
        settingsRepository.updateGoogleTasksAuthStatus(TasksAuthStatus.AUTH_REQUIRED)

        val candidate = createTestTaskCandidate()
        val result = destination.createTaskInternal(candidate, zoneId)

        assertTrue("Expected NeedsAttention result", result is DestinationExecutionResult.NeedsAttention)
        assertEquals("Zero HTTP POST requests should be sent when unauthorized", 0, fakeTasksClient.postRequests.size)

        // Room action must not be created as CREATED
        val action = taskActionDao.getAction(candidate.sourceMemoryId, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-personal-1")
        assertTrue("Action should be null or not CREATED", action == null || action.status != "CREATED")
    }

    // 3. Select correct Task List
    @Test
    fun selectCorrectTaskList() = runTest {
        settingsRepository.updateSelectedTaskList(listId = "list-work-99", listTitle = "工作專案")

        val candidate = createTestTaskCandidate()
        val result = destination.createTaskInternal(candidate, zoneId)

        assertTrue(result is DestinationExecutionResult.Success)
        assertEquals(1, fakeTasksClient.postRequests.size)
        assertEquals("list-work-99", fakeTasksClient.postRequests[0].taskListId)

        val action = taskActionDao.getAction(candidate.sourceMemoryId, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-work-99")
        assertNotNull(action)
        assertEquals("list-work-99", action!!.taskListId)
    }

    // 4. Persistent deduplication
    @Test
    fun persistentDeduplication() = runTest {
        val candidate = createTestTaskCandidate()

        // First execution
        val result1 = destination.createTaskInternal(candidate, zoneId)
        assertTrue(result1 is DestinationExecutionResult.Success)
        assertEquals(1, fakeTasksClient.postRequests.size)
        assertEquals(1, fakeTasksClient.remoteTasks.size)

        // Second execution for the same memory and list
        val result2 = destination.createTaskInternal(candidate, zoneId)
        assertTrue(result2 is DestinationExecutionResult.Success)
        val success2 = result2 as DestinationExecutionResult.Success
        assertTrue(success2.message.contains("idempotent"))

        // Remote tasks count must remain exactly 1 (no duplicate created!)
        assertEquals(1, fakeTasksClient.postRequests.size)
        assertEquals(1, fakeTasksClient.remoteTasks.size)
    }

    // 5. Concurrency and Process Restart
    @Test
    fun concurrencyAndProcessRestart() = runTest {
        val memoryId = "mem-crash-123"
        // Simulate previous process died while status was CREATING
        val staleReservation = TaskActionEntity(
            id = "action-crash-1",
            sourceMemoryId = memoryId,
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            status = "CREATING",
            title = "未完成任務",
            attemptCount = 1
        )
        taskActionDao.insert(staleReservation)

        val candidate = createTestTaskCandidate(sourceMemoryId = memoryId, title = "未完成任務")
        val result = destination.createTaskInternal(candidate, zoneId)

        // Detected concurrent in-flight action -> refuses duplicate execution
        assertTrue(result is DestinationExecutionResult.Failure)
        assertEquals(0, fakeTasksClient.postRequests.size)

        // After failure is marked, atomic claim allows retry
        taskActionDao.updateStatusAndError("action-crash-1", "FAILED", "Process killed")
        val retryResult = destination.createTaskInternal(candidate, zoneId)
        assertTrue(retryResult is DestinationExecutionResult.Success)
        assertEquals(1, fakeTasksClient.postRequests.size)
    }

    // 6. HTTP Error Handling (401, 429, 500)
    @Test
    fun httpErrorHandling401InvalidatesTokenAndRequiresAttention() = runTest {
        fakeTasksClient.shouldFailWithHttpException = TasksHttpException.Unauthorized(401, "Invalid token")

        val candidate = createTestTaskCandidate()
        val result = destination.createTaskInternal(candidate, zoneId)

        assertTrue(result is DestinationExecutionResult.NeedsAttention)
        assertTrue("Cached token must be invalidated on 401", fakeAuthManager.invalidateTokenCallCount > 0)
        assertEquals(TasksAuthStatus.AUTH_REQUIRED, fakeAuthManager.authStatus.value)

        val action = taskActionDao.getAction(candidate.sourceMemoryId, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-personal-1")
        assertEquals("FAILED", action!!.status)
    }

    @Test
    fun ambiguousHttp429And500DoNotBlindlyRetry() = runTest {
        // A response code alone cannot prove whether a POST was committed remotely.
        for (exception in listOf(
            TasksHttpException.RateLimited(429, "Too Many Requests"),
            TasksHttpException.ServerError(500, "Internal Server Error")
        )) {
            val candidate = createTestTaskCandidate(sourceMemoryId = "memory-${exception.statusCode}")
            fakeTasksClient.shouldFailWithHttpException = exception

            val first = destination.createTaskInternal(candidate, zoneId)
            assertTrue(first is DestinationExecutionResult.NeedsAttention)

            val persisted = taskActionDao.getAction(
                candidate.sourceMemoryId,
                GoogleTasksDestination.ACTION_TYPE_CREATE,
                "list-personal-1"
            )
            assertEquals("NEEDS_ATTENTION", persisted?.status)

            val postCount = fakeTasksClient.postRequests.size
            val repeated = destination.createTaskInternal(candidate, zoneId)
            assertTrue(repeated is DestinationExecutionResult.NeedsAttention)
            assertEquals("Uncertain POST must not be blindly retried", postCount, fakeTasksClient.postRequests.size)
        }
    }

    // 7. Response lost does not blindly duplicate (recovers if exists, needs attention if unconfirmed)
    @Test
    fun responseLostRecoversIfRemoteTaskExists() = runTest {
        // Simulates POST reached server, but response timed out
        fakeTasksClient.shouldThrowTimeoutOnCreate = true
        fakeTasksClient.timeoutDeliveredBeforeDisconnect = true

        val candidate = createTestTaskCandidate()
        val result = destination.createTaskInternal(candidate, zoneId)

        assertTrue("Should recover without duplicate creation", result is DestinationExecutionResult.Success)
        val success = result as DestinationExecutionResult.Success
        assertTrue(success.message.contains("未重複建立"))

        val action = taskActionDao.getAction(candidate.sourceMemoryId, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-personal-1")
        assertEquals("CREATED", action!!.status)
        assertEquals(1, fakeTasksClient.remoteTasks.size)
    }

    @Test
    fun responseLostMarksNeedsAttentionIfUnconfirmed() = runTest {
        // Simulates POST timed out and remote task was NOT created
        fakeTasksClient.shouldThrowTimeoutOnCreate = true
        fakeTasksClient.timeoutDeliveredBeforeDisconnect = false

        val candidate = createTestTaskCandidate()
        val result = destination.createTaskInternal(candidate, zoneId)

        assertTrue("Unconfirmed state must yield NeedsAttention", result is DestinationExecutionResult.NeedsAttention)
        val action = taskActionDao.getAction(candidate.sourceMemoryId, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-personal-1")
        assertEquals("NEEDS_ATTENTION", action!!.status)
        assertEquals("Zero remote tasks should exist", 0, fakeTasksClient.remoteTasks.size)
    }

    // 8. Original dueAt preserved
    @Test
    fun originalDueAtPreserved() = runTest {
        val exactZdt = ZonedDateTime.of(LocalDate.of(2026, 11, 10), LocalTime.of(15, 45), zoneId)
        val candidate = createTestTaskCandidate(dueAt = exactZdt)

        val result = destination.createTaskInternal(candidate, zoneId)
        assertTrue(result is DestinationExecutionResult.Success)

        val action = taskActionDao.getAction(candidate.sourceMemoryId, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-personal-1")
        assertNotNull(action)
        assertEquals(exactZdt.toInstant().toEpochMilli(), action!!.originalDueAt)
        assertEquals("2026-11-10T00:00:00.000Z", action.googleDue)
        assertTrue(action.notes!!.contains("2026-11-10 15:45"))
    }

    // 9. Canonical Memory always preserved
    @Test
    fun canonicalMemoryAlwaysPreservedOnDestinationFailure() = runTest {
        fakeTasksClient.shouldFailWithHttpException = TasksHttpException.ServerError(500, "Server exploded")

        val memory = MemoryItem(
            id = "mem-canonical-999",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦重要事項",
            rawText = "TODO: 重要專案提案\n截止: 2026-10-30"
        )
        memoryRepository.saveMemory(memory)

        val candidate = createTestTaskCandidate(sourceMemoryId = memory.id, title = "重要專案提案")
        val result = destination.createTaskInternal(candidate, zoneId)

        assertTrue("Uncertain remote state requires attention", result is DestinationExecutionResult.NeedsAttention)

        // Canonical memory must remain in repository untouched!
        val preserved = memoryRepository.getMemoryById(memory.id).first()
        assertNotNull("Canonical Memory must never be deleted on destination failure", preserved)
        assertEquals("重要專案提案", candidate.title)
    }

    // 10. Integration with ActionRouter & persistent deduplication
    @Test
    fun integrationWithActionRouterAndPersistentDeduplication() = runTest {
        val classifier = DeterministicActionClassifier(
            eventExtractor = DeterministicEventExtractor()
        )
        val destinationRegistry = object : DestinationRegistry {
            override fun getDestination(type: DestinationType) = if (type == DestinationType.TASK) destination else null
            override fun register(destination: com.cayana.actions.destination.ActionDestination) {}
        }
        val routingHistoryTracker = DefaultActionRoutingHistoryTracker(
            taskActionDao = taskActionDao
        )
        val router = DefaultActionRouter(
            classifier = classifier,
            destinationRegistry = destinationRegistry,
            routingHistoryTracker = routingHistoryTracker,
            memoryRepository = memoryRepository,
            logger = DefaultCayanaLogger()
        )

        val memory = MemoryItem(
            id = "mem-route-task-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 繳交成果報告 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(memory)

        // First route & execute
        val decisions1 = router.route(memory, memory.rawText, fixedRefTime, zoneId)
        val taskDecision = decisions1.filterIsInstance<RoutingDecision.RouteAuto>().firstOrNull()
        assertNotNull(taskDecision)

        val execResult = router.executeDecision(taskDecision!!, memory)
        assertTrue(execResult is DestinationExecutionResult.Success)
        assertEquals(1, fakeTasksClient.remoteTasks.size)

        // Second route: persistent tracker detects existing Room record -> routes as MemoryOnly
        val decisions2 = router.route(memory, memory.rawText, fixedRefTime, zoneId)
        val duplicateDecision = decisions2.filterIsInstance<RoutingDecision.MemoryOnly>().firstOrNull()
        assertNotNull("Second routing attempt must yield MemoryOnly due to persistent deduplication", duplicateDecision)
        assertTrue(duplicateDecision!!.reason.contains("already exists"))

        router.executeDecision(duplicateDecision, memory)
        assertEquals("Remote tasks must strictly remain 1", 1, fakeTasksClient.remoteTasks.size)
    }

    @Test
    fun repeatedIgnoreCannotOverwriteCreated() = runTest {
        val memory = MemoryItem(
            id = "mem-created-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 完成專案報告"
        )
        memoryRepository.saveMemory(memory)

        // 1. Create task successfully -> CREATED status
        val candidate = createTestTaskCandidate(sourceMemoryId = memory.id, title = "完成專案報告")
        val execResult = destination.createTask(candidate)
        assertTrue(execResult is DestinationExecutionResult.Success)

        val createdAction = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-personal-1")
        assertNotNull(createdAction)
        assertEquals("CREATED", createdAction!!.status)

        // 2. An old notification or duplicate event attempts to claim or update to IGNORED
        val rowsAffected = taskActionDao.claimIgnoreAction(createdAction.id)
        assertEquals("claimIgnoreAction must affect 0 rows when status is already CREATED", 0, rowsAffected)

        val updateStatusRows = taskActionDao.updateStatus(createdAction.id, "IGNORED")
        assertEquals("updateStatus must not overwrite terminal CREATED status", 0, updateStatusRows)

        val reloaded = taskActionDao.getActionById(createdAction.id)
        assertEquals("Status must remain strictly CREATED", "CREATED", reloaded?.status)
    }

    @Test
    fun concurrentConfirmAndIgnoreIsAtomic() = runTest {
        val actionId = UUID.randomUUID().toString()
        val action = TaskActionEntity(
            id = actionId,
            sourceMemoryId = "mem-atomic-race-1",
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            taskListTitle = "個人待辦",
            status = "CONFIRM_PENDING",
            title = "待辦項目",
            attemptCount = 0
        )
        taskActionDao.insert(action)

        // Atomically claim in racing order: exactly one succeeds
        val confirmClaimed = taskActionDao.claimConfirmAction(actionId)
        val ignoreClaimed = taskActionDao.claimIgnoreAction(actionId)

        assertEquals("First claim (confirm) must succeed", 1, confirmClaimed)
        assertEquals("Second claim (ignore) must fail because status is no longer CONFIRM_PENDING", 0, ignoreClaimed)

        val finalAction = taskActionDao.getActionById(actionId)
        assertEquals("CREATING", finalAction?.status)
    }

    @Test
    fun changedTaskListCannotRedirectPendingAction() = runTest {
        val actionId = UUID.randomUUID().toString()
        val pendingAction = TaskActionEntity(
            id = actionId,
            sourceMemoryId = "mem-changed-list-1",
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            taskListTitle = "個人待辦",
            status = "CONFIRM_PENDING",
            title = "機密工作待辦",
            attemptCount = 0
        )
        taskActionDao.insert(pendingAction)

        // User switches the destination task list in Settings to "list-work-2"
        settingsRepository.updateSelectedTaskList("list-work-2", "公開工作清單")

        // User now triggers confirmation for the pending action
        val result = destination.executeConfirmedTask(actionId)

        // Must reject execution to avoid sending private data to the wrong destination list!
        assertTrue("Execution must fail when destination task list changed", result is DestinationExecutionResult.Failure)
        assertEquals(0, fakeTasksClient.remoteTasks.size)

        val updatedAction = taskActionDao.getActionById(actionId)
        assertEquals("CANCELLED", updatedAction?.status)
        assertTrue(updatedAction?.lastError?.contains("目標待辦清單已變更") == true)
    }

    @Test
    fun staleCreatingUsesLastStateTransition() = runTest {
        val now = System.currentTimeMillis()
        val memory = MemoryItem(
            id = "mem-stale-test-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 繳交報告"
        )
        memoryRepository.saveMemory(memory)

        // Case 1: Action created 60 seconds ago, BUT last state transition (updatedAt) was 5 seconds ago
        val recentAction = TaskActionEntity(
            id = "action-recent-update",
            sourceMemoryId = memory.id,
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            taskListTitle = "個人待辦",
            status = "CREATING",
            title = "繳交報告",
            createdAt = now - 60_000L,
            updatedAt = now - 5_000L,
            attemptCount = 1
        )
        taskActionDao.insert(recentAction)

        val candidate = createTestTaskCandidate(sourceMemoryId = memory.id, title = "繳交報告")
        val resultRecent = destination.createTask(candidate)
        // Since updatedAt is only 5s ago (< 30s), it is in flight and NOT stale!
        assertTrue("Recent state transition must not be marked stale", resultRecent is DestinationExecutionResult.Failure)
        assertEquals("Task creation is currently in progress", (resultRecent as DestinationExecutionResult.Failure).reason)

        // Case 2: Action updatedAt is 40 seconds ago (> 30s) -> it IS stale and should be reconciled
        database.openHelper.writableDatabase.execSQL(
            "UPDATE task_actions SET updatedAt = ${now - 40_000L} WHERE id = 'action-recent-update'"
        )

        // Now createTask should detect stale CREATING action and reconcile
        val resultStale = destination.createTask(candidate)
        // Since remote item is not found, reconcileUncertainPost returns NeedsAttention without creating duplicate
        assertTrue("Stale CREATING action must trigger reconciliation", resultStale is DestinationExecutionResult.NeedsAttention)
    }

    @Test
    fun unconfirmedPostNeverBlindlyRetries() = runTest {
        val memory = MemoryItem(
            id = "mem-unconfirmed-post-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 重要款項轉帳"
        )
        memoryRepository.saveMemory(memory)

        // Simulate an action that was previously attempted but entered NEEDS_ATTENTION due to ambiguous POST
        val uncertainAction = TaskActionEntity(
            id = "action-uncertain-1",
            sourceMemoryId = memory.id,
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            taskListTitle = "個人待辦",
            status = "NEEDS_ATTENTION",
            title = "重要款項轉帳",
            attemptCount = 1,
            lastError = "Remote POST outcome unconfirmed"
        )
        taskActionDao.insert(uncertainAction)

        val initialPostCount = fakeTasksClient.postRequests.size

        val candidate = createTestTaskCandidate(sourceMemoryId = memory.id, title = "重要款項轉帳")
        val result = destination.createTask(candidate)

        // Must return NeedsAttention and NEVER blindly call createTask again!
        assertTrue("Unconfirmed action must require attention instead of blind retry", result is DestinationExecutionResult.NeedsAttention)
        assertEquals("Remote POST count must not increase", initialPostCount, fakeTasksClient.postRequests.size)

        val reloaded = taskActionDao.getActionById("action-uncertain-1")
        assertEquals("NEEDS_ATTENTION", reloaded?.status)
    }

    @Test
    fun staleActionNeverRedirectsToNewTaskList() = runTest {
        val now = System.currentTimeMillis()
        val memory = MemoryItem(
            id = "mem-stale-redirect-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 機密資料備份"
        )
        memoryRepository.saveMemory(memory)

        // Action was started on original list "list-personal-1"
        val staleAction = TaskActionEntity(
            id = "action-stale-redirect-1",
            sourceMemoryId = memory.id,
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            taskListTitle = "個人待辦",
            status = "CREATING",
            title = "機密資料備份",
            createdAt = now - 60_000L,
            updatedAt = now - 40_000L,
            attemptCount = 1
        )
        taskActionDao.insert(staleAction)

        // User subsequently switches active task list in settings to "list-work-2"
        settingsRepository.updateSelectedTaskList("list-work-2", "公開工作清單")

        // Calling createTask for this memory must reconcile against original list and never POST to new list
        val candidate = createTestTaskCandidate(sourceMemoryId = memory.id, title = "機密資料備份")
        val result = destination.createTask(candidate)

        assertEquals("Must not make any POST requests to new list", 0, fakeTasksClient.postRequests.size)
        assertTrue("Unconfirmed remote post must return NeedsAttention", result is DestinationExecutionResult.NeedsAttention)

        val reloaded = taskActionDao.getActionById("action-stale-redirect-1")
        assertEquals("NEEDS_ATTENTION", reloaded?.status)
        assertEquals("list-personal-1", reloaded?.taskListId)
    }

    @Test
    fun staleActionCannotCreateDuplicateTask() = runTest {
        val now = System.currentTimeMillis()
        val memory = MemoryItem(
            id = "mem-stale-duplicate-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 買咖啡豆"
        )
        memoryRepository.saveMemory(memory)

        // Remote task was actually created previously
        val remoteItem = GoogleTaskItem(
            id = "remote-coffee-1",
            title = "買咖啡豆",
            notes = "cayana://memory/${memory.id}"
        )
        fakeTasksClient.remoteTasks.add(remoteItem)

        // But local state was left in CREATING (e.g. process died or network timed out after POST succeeded)
        val staleAction = TaskActionEntity(
            id = "action-stale-dup-1",
            sourceMemoryId = memory.id,
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            taskListTitle = "個人待辦",
            status = "CREATING",
            title = "買咖啡豆",
            createdAt = now - 60_000L,
            updatedAt = now - 40_000L,
            attemptCount = 1
        )
        taskActionDao.insert(staleAction)

        val initialPostCount = fakeTasksClient.postRequests.size

        val candidate = createTestTaskCandidate(sourceMemoryId = memory.id, title = "買咖啡豆")
        val result = destination.createTask(candidate)

        // Must succeed by discovering existing remote task, NOT by creating duplicate
        assertTrue("Reconciled existing remote task must succeed", result is DestinationExecutionResult.Success)
        assertEquals("Zero new POST requests should be issued", initialPostCount, fakeTasksClient.postRequests.size)

        val reloaded = taskActionDao.getActionById("action-stale-dup-1")
        assertEquals("CREATED", reloaded?.status)
        assertEquals("remote-coffee-1", reloaded?.destinationTaskId)
    }

    @Test
    fun confirmedTaskThrownExceptionReconcilesSafely() = runTest {
        val actionId = UUID.randomUUID().toString()
        val memory = MemoryItem(
            id = "mem-throw-test-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 送出報價單"
        )
        memoryRepository.saveMemory(memory)

        val action = TaskActionEntity(
            id = actionId,
            sourceMemoryId = memory.id,
            actionType = GoogleTasksDestination.ACTION_TYPE_CREATE,
            taskListId = "list-personal-1",
            taskListTitle = "個人待辦",
            status = "CONFIRM_PENDING",
            title = "送出報價單",
            attemptCount = 0
        )
        taskActionDao.insert(action)

        // Configure client to throw SocketTimeoutException during createTask
        fakeTasksClient.shouldThrowTimeoutOnCreate = true
        fakeTasksClient.timeoutDeliveredBeforeDisconnect = false // Remote task was NOT delivered

        val result = destination.executeConfirmedTask(actionId)

        // Destination must catch the thrown exception, reconcile via findTaskByMemoryRef,
        // and when not found remotely, safely mark NEEDS_ATTENTION instead of crashing or staying CREATING
        assertTrue("Execution must result in NeedsAttention or Failure without crashing", result is DestinationExecutionResult.NeedsAttention)

        val reloaded = taskActionDao.getActionById(actionId)
        assertNotNull(reloaded)
        assertEquals("NEEDS_ATTENTION", reloaded?.status)
        assertFalse("Error message must not contain private sensitive data", reloaded?.lastError?.contains("cayana://") == true)
    }

    // Test fakes
    class FakeTasksAuthorizationManager(
        initialStatus: TasksAuthStatus = TasksAuthStatus.DISCONNECTED,
        var tokenToReturn: String? = "mock-valid-google-tasks-token"
    ) : TasksAuthorizationManager {
        private val _authStatus = MutableStateFlow(initialStatus)
        override val authStatus: StateFlow<TasksAuthStatus> = _authStatus.asStateFlow()
        var invalidateTokenCallCount = 0

        fun setStatus(status: TasksAuthStatus) {
            _authStatus.value = status
        }

        override suspend fun getAccessToken(): Result<String> {
            val t = tokenToReturn
            return if (_authStatus.value == TasksAuthStatus.CONNECTED && t != null) {
                Result.Success(t)
            } else {
                _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
                Result.Error(TasksAuthRequiredException())
            }
        }

        override suspend fun getAuthorizationIntentSender(): Result<IntentSender?> = Result.Success(null)

        override suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> {
            _authStatus.value = TasksAuthStatus.CONNECTED
            return Result.Success(Unit)
        }

        override suspend fun invalidateCachedAccessToken() {
            invalidateTokenCallCount++
            tokenToReturn = null
        }

        override suspend fun disconnect(): Result<Unit> {
            _authStatus.value = TasksAuthStatus.DISCONNECTED
            tokenToReturn = null
            return Result.Success(Unit)
        }

        override suspend fun refreshAuthStatus(): TasksAuthStatus = _authStatus.value

        override fun markAuthRequired() {
            _authStatus.value = TasksAuthStatus.AUTH_REQUIRED
        }
    }

    class FakeGoogleTasksClient : GoogleTasksClient {
        val taskLists = mutableListOf(GoogleTaskList(id = "list-personal-1", title = "個人待辦"))
        val remoteTasks = mutableListOf<GoogleTaskItem>()
        val postRequests = mutableListOf<PostRequestRecord>()
        var shouldThrowTimeoutOnCreate = false
        var timeoutDeliveredBeforeDisconnect = false
        var shouldFailWithHttpException: TasksHttpException? = null

        data class PostRequestRecord(
            val token: String,
            val taskListId: String,
            val title: String,
            val notes: String?,
            val dueRfc3339: String?
        )

        override suspend fun getTaskLists(accessToken: String): Result<List<GoogleTaskList>> = Result.Success(taskLists)

        override suspend fun createTask(
            accessToken: String,
            taskListId: String,
            title: String,
            notes: String?,
            dueRfc3339: String?
        ): Result<GoogleTaskItem> {
            postRequests.add(PostRequestRecord(accessToken, taskListId, title, notes, dueRfc3339))

            if (shouldThrowTimeoutOnCreate) {
                if (timeoutDeliveredBeforeDisconnect) {
                    val item = GoogleTaskItem(
                        id = "remote-task-${remoteTasks.size + 1}",
                        title = title,
                        notes = notes,
                        due = dueRfc3339
                    )
                    remoteTasks.add(item)
                }
                throw SocketTimeoutException("Read timed out waiting for Google Tasks API response")
            }

            shouldFailWithHttpException?.let {
                return Result.Error(it)
            }

            val item = GoogleTaskItem(
                id = "remote-task-${remoteTasks.size + 1}",
                title = title,
                notes = notes,
                due = dueRfc3339
            )
            remoteTasks.add(item)
            return Result.Success(item)
        }

        override suspend fun findTaskByMemoryRef(
            accessToken: String,
            taskListId: String,
            sourceMemoryId: String,
            title: String
        ): Result<GoogleTaskItem?> {
            val found = remoteTasks.find {
                it.notes?.contains("cayana://memory/$sourceMemoryId") == true ||
                        (it.title == title && it.notes?.contains(sourceMemoryId) == true)
            }
            return Result.Success(found)
        }
    }
}
