package com.cayana.actions.coordinator

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
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
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
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
                selectedTaskListTitle = "測試清單",
                autoTasksEligibilityEpoch = 1L
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
            logger = logger,
            memoryRepository = memoryRepository
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

    @Test
    fun pendingConfirmationCannotAutoExecute() = runTest {
        val memory = MemoryItem(
            id = "mem-pending-confirm-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "可能要記得繳水費"
        )
        memoryRepository.saveMemory(memory)

        // 1. Process as low confidence -> CONFIRM_PENDING
        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertTrue("Initial confirmation notification posted", handled)
        val action = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-test-1")
        assertNotNull(action)
        assertEquals("CONFIRM_PENDING", action!!.status)
        assertEquals(0, fakeTasksClient.createdTasks.size)

        // 2. Try to auto-execute via destination for this pending candidate
        val candidate = ActionCandidate(
            type = ActionCandidateType.TASK,
            title = "繳水費",
            confidence = 0.95f,
            sourceMemoryId = memory.id,
            requiresConfirmation = false
        )
        val result = googleTasksDestination.createTask(candidate)
        assertTrue("Auto execution must be skipped for pending confirmation", result is DestinationExecutionResult.Skipped)

        // 3. Remote client must still have 0 tasks created, status remains CONFIRM_PENDING
        assertEquals(0, fakeTasksClient.createdTasks.size)
        val afterAction = taskActionDao.getActionById(action.id)
        assertEquals("CONFIRM_PENDING", afterAction?.status)
    }

    @Test
    fun reclassifiedMemoryCannotBypassConfirmation() = runTest {
        val memory = MemoryItem(
            id = "mem-reclass-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "可能要記得繳電話費"
        )
        memoryRepository.saveMemory(memory)

        // 1. First run generates CONFIRM_PENDING
        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        val action = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-test-1")
        assertNotNull(action)
        assertEquals("CONFIRM_PENDING", action!!.status)
        assertEquals(0, fakeTasksClient.createdTasks.size)

        // 2. Re-process with high confidence text for the same memory
        val reclassifiedRawText = "TODO: 繳電話費 截止: 2026-10-25 18:00"
        val handledSecond = coordinator.process(memory, reclassifiedRawText, fixedRefTime, zoneId)
        assertFalse("Reclassified memory cannot auto-route or bypass confirmation", handledSecond)

        // Remote tasks must remain 0 and action must remain CONFIRM_PENDING
        assertEquals(0, fakeTasksClient.createdTasks.size)
        val afterAction = taskActionDao.getActionById(action.id)
        assertEquals("CONFIRM_PENDING", afterAction?.status)
    }

    @Test
    fun notificationDeniedHasSafeFallback() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = Shadows.shadowOf(notificationManager)
        shadowNotificationManager.setNotificationsEnabled(false)

        val memory = MemoryItem(
            id = "mem-denied-notif-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "可能要記得繳水費"
        )
        memoryRepository.saveMemory(memory)

        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse("When notification cannot be shown, coordinator must return false", handled)

        val action = taskActionDao.getAction(memory.id, GoogleTasksDestination.ACTION_TYPE_CREATE, "list-test-1")
        assertNotNull(action)
        assertEquals("NEEDS_ATTENTION", action!!.status)
        assertTrue(action.lastError?.contains("通知未開啟或發送失敗") == true)
    }

    @Test
    fun processDeathAfterMemoryPersistRecoversRouting() = runTest {
        // Step 1: Memory was saved to database (e.g. screenshot OCR succeeded and persisted)
        val memory = MemoryItem(
            id = "mem-process-death-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 提交專案結案報告 截止: 2026-10-30 17:00"
        )
        memoryRepository.saveMemory(memory)

        // Step 2: But process died before coordinator.process() could run!
        // No TaskActionEntity exists for this memory yet.
        assertTrue(taskActionDao.getActionsForMemory(memory.id).isEmpty())
        assertEquals(0, fakeTasksClient.createdTasks.size)

        // Step 3: App restarts / coordinator recovers in-flight actions
        coordinator.reconcileInFlightActions(recoverMemories = true)

        // Step 4: Routing must be recovered and task created!
        assertEquals(1, fakeTasksClient.createdTasks.size)
        val created = fakeTasksClient.createdTasks.first()
        assertTrue(created.title.contains("提交專案結案報告"))

        val actions = taskActionDao.getActionsForMemory(memory.id)
        assertFalse(actions.isEmpty())
        val action = actions.find { it.actionType == GoogleTasksDestination.ACTION_TYPE_CREATE }
        assertNotNull(action)
        assertEquals("CREATED", action!!.status)
    }

    @Test
    fun duplicateWorkDoesNotDuplicatePOST() = runTest {
        val memory = MemoryItem(
            id = "mem-duplicate-work-1",
            sourceType = SourceType.SCREENSHOT,
            title = "待辦",
            rawText = "TODO: 買牛奶 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(memory)

        // First execution routes and creates the task
        val handled1 = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertTrue(handled1)
        assertEquals(1, fakeTasksClient.createdTasks.size)

        // Second duplicate call with the same memory (e.g. repeated worker, duplicate event)
        val handled2 = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse("Duplicate work must be skipped", handled2)
        assertEquals("Zero additional POSTs must be made", 1, fakeTasksClient.createdTasks.size)

        // Even if reconcileInFlightActions runs, it must not duplicate
        coordinator.reconcileInFlightActions(recoverMemories = true)
        assertEquals("Reconciliation must not create duplicate POST", 1, fakeTasksClient.createdTasks.size)
    }

    @Test
    fun historicalMemoryNeverAutomaticallyBackfilled() = runTest {
        val now = System.currentTimeMillis()
        // Historical memory captured 7 days ago (before Google Tasks was enabled)
        val historicalMemory = MemoryItem(
            id = "mem-hist-backfill-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = now - 7 * 24 * 3600_000L,
            capturedAt = now - 7 * 24 * 3600_000L,
            title = "待辦",
            rawText = "TODO: 一週前的重要歷史待辦 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(historicalMemory)

        // User enables Google Tasks now: eligibility epoch is set to current time
        settingsRepository.updateGoogleTasksEligibilityEpoch(now)

        // Reconciliation / recovery runs
        coordinator.reconcileInFlightActions(recoverMemories = true)

        // Historical memory must NEVER be backfilled or uploaded to Google Tasks
        assertEquals("Historical memory must not create remote task", 0, fakeTasksClient.createdTasks.size)
        assertTrue("No action entity should be created for historical memory", taskActionDao.getActionsForMemory(historicalMemory.id).isEmpty())
    }

    @Test
    fun processDeathRecoversOnlyEligibleMemory() = runTest {
        val epochTime = 50_000L
        settingsRepository.updateGoogleTasksEligibilityEpoch(epochTime)

        // M1: Historical memory created before epoch (e.g. 10_000L)
        val m1Historical = MemoryItem(
            id = "mem-eligible-test-m1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 10_000L,
            capturedAt = 10_000L,
            title = "歷史待辦",
            rawText = "TODO: 歷史舊待辦 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(m1Historical)

        // M2: Eligible memory captured during active connection after epoch (e.g. 60_000L)
        val m2Eligible = MemoryItem(
            id = "mem-eligible-test-m2",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 60_000L,
            capturedAt = 60_000L,
            title = "新拍攝待辦",
            rawText = "TODO: 新拍攝待辦 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(m2Eligible)

        // Process death occurred before either could be routed
        assertEquals(0, fakeTasksClient.createdTasks.size)
        assertTrue(taskActionDao.getActionsForMemory(m1Historical.id).isEmpty())
        assertTrue(taskActionDao.getActionsForMemory(m2Eligible.id).isEmpty())

        // Recovery runs on restart
        coordinator.reconcileInFlightActions(recoverMemories = true)

        // Only M2 is recovered and created; M1 is untouched
        assertEquals("Only eligible memory M2 should be created remotely", 1, fakeTasksClient.createdTasks.size)
        assertTrue("Created task must belong to M2", fakeTasksClient.createdTasks.first().title.contains("新拍攝待辦"))

        assertTrue("M1 must have zero actions", taskActionDao.getActionsForMemory(m1Historical.id).isEmpty())
        val m2Actions = taskActionDao.getActionsForMemory(m2Eligible.id)
        assertEquals(1, m2Actions.size)
        assertEquals("CREATED", m2Actions.first().status)
    }

    @Test
    fun delayedHistoricalScreenshotNeverUploads() = runTest {
        val epochTime = 50_000L
        settingsRepository.updateAutoTasksEnabled(true)
        settingsRepository.updateAutoTasksEligibilityEpoch(epochTime)

        // Historical screenshot taken before authorization (capturedAt = 10_000L)
        // but imported/saved to DB later (createdAt = 60_000L > epochTime)
        val delayedHistorical = MemoryItem(
            id = "mem-delayed-historical",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 10_000L,
            createdAt = 60_000L,
            title = "延遲匯入歷史待辦",
            rawText = "TODO: 歷史延遲待辦 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(delayedHistorical)

        // Run recovery
        coordinator.reconcileInFlightActions(recoverMemories = true)

        // Must never upload delayed historical screenshot
        assertEquals("Delayed historical screenshot must not create task", 0, fakeTasksClient.createdTasks.size)
        assertTrue("No action should be created for delayed historical", taskActionDao.getActionsForMemory(delayedHistorical.id).isEmpty())
    }

    @Test
    fun eligibilityOrderingMatchesQuery() = runTest {
        val epochTime = 50_000L
        settingsRepository.updateAutoTasksEnabled(true)
        settingsRepository.updateAutoTasksEligibilityEpoch(epochTime)

        val mOld = MemoryItem(
            id = "mem-order-old",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 20_000L,
            createdAt = 20_000L,
            title = "舊記憶",
            rawText = "TODO: 舊記憶 截止: 2026-10-25 18:00"
        )
        val mNewer = MemoryItem(
            id = "mem-order-newer",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 60_000L,
            createdAt = 60_000L,
            title = "較新記憶",
            rawText = "TODO: 較新記憶 截止: 2026-10-25 18:00"
        )
        val mNewest = MemoryItem(
            id = "mem-order-newest",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 80_000L,
            createdAt = 80_000L,
            title = "最新記憶",
            rawText = "TODO: 最新記憶 截止: 2026-10-25 18:00"
        )

        memoryRepository.saveMemory(mOld)
        memoryRepository.saveMemory(mNewer)
        memoryRepository.saveMemory(mNewest)

        // Verify the query ordering directly: capturedAt DESC, id DESC
        val queried = memoryRepository.getMemoriesCapturedSince(SourceType.SCREENSHOT, sinceCapturedAt = epochTime, limit = 50)
        assertEquals(2, queried.size)
        assertEquals("mem-order-newest", queried[0].id)
        assertEquals("mem-order-newer", queried[1].id)

        // Run recovery: both eligible memories are processed in order, mOld is untouched
        val recovered = coordinator.recoverUnroutedMemories(memoryRepository)
        assertEquals(2, recovered)
        assertEquals(2, fakeTasksClient.createdTasks.size)
        assertTrue(taskActionDao.getActionsForMemory(mOld.id).isEmpty())
    }

    @Test
    fun connectionWithoutAutoEnableDoesNotBackfill() = runTest {
        // Connected to OAuth, but autoTasksEnabled = false
        settingsRepository.updateGoogleTasksAuthStatus(TasksAuthStatus.CONNECTED)
        settingsRepository.updateAutoTasksEnabled(false)

        val memory = MemoryItem(
            id = "mem-no-auto-backfill",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 60_000L,
            createdAt = 60_000L,
            title = "未開啟自動傳送待辦",
            rawText = "TODO: 未開啟自動傳送待辦 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(memory)

        // Run recovery
        val recovered = coordinator.recoverUnroutedMemories(memoryRepository)
        assertEquals("Must not recover or upload when autoTasksEnabled is false", 0, recovered)
        assertEquals(0, fakeTasksClient.createdTasks.size)
        assertTrue(taskActionDao.getActionsForMemory(memory.id).isEmpty())
    }

    @Test
    fun reEnableDoesNotBackfillDisabledInterval() = runTest {
        // 1. Initial enable
        settingsRepository.updateAutoTasksEnabled(true)
        val initialEpoch = settingsRepository.getSettings().first().autoTasksEligibilityEpoch
        assertTrue(initialEpoch > 0)

        // 2. User disables auto tasks
        settingsRepository.updateAutoTasksEnabled(false)
        assertEquals(0L, settingsRepository.getSettings().first().autoTasksEligibilityEpoch)

        // 3. While disabled, a screenshot is captured and saved
        val disabledIntervalTime = System.currentTimeMillis()
        val disabledMemory = MemoryItem(
            id = "mem-disabled-interval",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = disabledIntervalTime,
            createdAt = disabledIntervalTime,
            title = "停用期間拍攝的待辦",
            rawText = "TODO: 停用期間待辦 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(disabledMemory)

        // Simulate a tiny delay so re-enable epoch > disabledIntervalTime
        Thread.sleep(10)

        // 4. User re-enables auto tasks: epoch is reset to current time
        settingsRepository.updateAutoTasksEnabled(true)
        val reEnabledEpoch = settingsRepository.getSettings().first().autoTasksEligibilityEpoch
        assertTrue("Re-enabled epoch must be after disabled screenshot", reEnabledEpoch > disabledIntervalTime)

        // 5. Run recovery
        val recovered = coordinator.recoverUnroutedMemories(memoryRepository)
        assertEquals("Screenshots captured in disabled interval must not be backfilled", 0, recovered)
        assertEquals(0, fakeTasksClient.createdTasks.size)
        assertTrue(taskActionDao.getActionsForMemory(disabledMemory.id).isEmpty())
    }

    @Test
    fun eligibleProcessDeathStillRecovers() = runTest {
        val epochTime = 50_000L
        settingsRepository.updateAutoTasksEnabled(true)
        settingsRepository.updateAutoTasksEligibilityEpoch(epochTime)

        // Memory captured after epoch
        val eligibleMemory = MemoryItem(
            id = "mem-process-death-eligible",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 60_000L,
            createdAt = 60_000L,
            title = "待恢復待辦",
            rawText = "TODO: 待恢復待辦 截止: 2026-10-25 18:00"
        )
        memoryRepository.saveMemory(eligibleMemory)

        // Process death: no actions recorded yet
        assertTrue(taskActionDao.getActionsForMemory(eligibleMemory.id).isEmpty())
        assertEquals(0, fakeTasksClient.createdTasks.size)

        // On reboot/restart, recovery runs
        val recovered = coordinator.recoverUnroutedMemories(memoryRepository)
        assertEquals(1, recovered)
        assertEquals(1, fakeTasksClient.createdTasks.size)
        val actions = taskActionDao.getActionsForMemory(eligibleMemory.id)
        assertEquals(1, actions.size)
        assertEquals("CREATED", actions.first().status)
    }

    @Test
    fun normalIngestionRespectsEligibilityBoundary() = runTest {
        val epochTime = 50_000L
        settingsRepository.updateAutoTasksEnabled(true)
        settingsRepository.updateAutoTasksEligibilityEpoch(epochTime)

        // Historical memory captured before epoch (20_000L < 50_000L)
        val historicalMemory = MemoryItem(
            id = "mem-normal-ingestion-historical",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 20_000L,
            createdAt = 20_000L,
            title = "歷史截圖",
            rawText = "TODO: 歷史截圖普通進程 截止: 2026-10-25 18:00"
        )

        // Directly call coordinator.process() as normal ingestion would
        val handled = coordinator.process(historicalMemory, historicalMemory.rawText)

        // Must respect boundary: not uploaded to Google Tasks
        assertFalse("Normal ingestion must not auto-route ineligible historical memory", handled)
        assertEquals("No remote task created for historical memory", 0, fakeTasksClient.createdTasks.size)
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
