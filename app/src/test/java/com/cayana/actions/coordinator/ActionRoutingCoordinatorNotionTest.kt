package com.cayana.actions.coordinator

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.actions.classifier.ActionClassifier
import com.cayana.actions.destination.DefaultDestinationRegistry
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.notion.auth.FakeCayanaLogger
import com.cayana.actions.notion.auth.NotionAuthStatus
import com.cayana.actions.notion.client.NotionCreatedPage
import com.cayana.actions.notion.destination.FakeNotionAuthManager
import com.cayana.actions.notion.destination.FakeNotionClient
import com.cayana.actions.notion.destination.NotionDestination
import com.cayana.actions.router.DefaultActionRouter
import com.cayana.actions.router.DefaultActionRoutingHistoryTracker
import com.cayana.core.common.Result
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.NotionTransmissionPolicy
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionRoutingCoordinatorNotionTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var fakeAuthManager: FakeNotionAuthManager
    private lateinit var fakeNotionClient: FakeNotionClient
    private lateinit var notionDestination: NotionDestination
    private lateinit var actionRouter: DefaultActionRouter
    private lateinit var coordinator: ActionRoutingCoordinator
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var logger: FakeCayanaLogger

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        fakeAuthManager = FakeNotionAuthManager()
        fakeAuthManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        fakeAuthManager.tokenToReturn = "valid_token"

        fakeNotionClient = FakeNotionClient()
        logger = FakeCayanaLogger()

        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                isNotionConnected = true,
                notionAuthStatus = NotionAuthStatus.CONNECTED,
                notionTransmissionPolicy = NotionTransmissionPolicy.OFF,
                autoNotionEnabled = false,
                selectedNotionTargetId = "page_target_123",
                selectedNotionTargetTitle = "Default Page",
                selectedNotionTargetType = "PAGE",
                autoNotionSources = setOf(SourceType.SCREENSHOT),
                autoNotionEligibilityEpoch = 1000L
            )
        )

        notionDestination = NotionDestination(
            notionActionDao = database.notionActionDao(),
            notionAuthManager = fakeAuthManager,
            notionClient = fakeNotionClient,
            settingsRepository = settingsRepository,
            logger = logger
        )

        val destinationRegistry = DefaultDestinationRegistry(
            destinations = listOf(notionDestination)
        )

        val classifier = object : ActionClassifier {
            override fun classify(
                memoryItem: MemoryItem,
                rawText: String?,
                referenceTime: Instant,
                zoneId: ZoneId
            ): List<ActionCandidate> {
                return listOf(
                    ActionCandidate(
                        type = ActionCandidateType.NOTE,
                        title = memoryItem.title ?: "Candidate Note",
                        notes = rawText,
                        confidence = 0.95f,
                        sourceMemoryId = memoryItem.id,
                        requiresConfirmation = false
                    )
                )
            }
        }

        val routingHistoryTracker = DefaultActionRoutingHistoryTracker(
            calendarActionDao = database.calendarActionDao(),
            taskActionDao = database.taskActionDao(),
            notionActionDao = database.notionActionDao()
        )

        memoryRepository = FakeMemoryRepository()

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
            taskActionDao = database.taskActionDao(),
            googleTasksDestination = null,
            notionActionDao = database.notionActionDao(),
            notionDestination = notionDestination,
            settingsRepository = settingsRepository,
            memoryRepository = memoryRepository,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun policyOffNeverUploadsOrPostsNoteAction() = runTest {
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.OFF)

        val memory = MemoryItem(
            id = "mem_off_1",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 2000L,
            rawText = "Note to ignore",
            title = "Test Note"
        )

        val handled = coordinator.process(memory, memory.rawText)

        assertFalse(handled)
        assertEquals(0, fakeNotionClient.createdPages.size)
        val actions = database.notionActionDao().getActionsForMemory("mem_off_1")
        assertEquals(0, actions.size)
    }

    @Test
    fun policyAskEachTimeInsertsConfirmPendingAndPostsNotification() = runTest {
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)

        val memory = MemoryItem(
            id = "mem_ask_1",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 2000L,
            rawText = "Note requiring user confirmation",
            title = "Ask Each Time Note"
        )

        val handled = coordinator.process(memory, memory.rawText)

        assertTrue(handled)
        assertEquals(0, fakeNotionClient.createdPages.size)

        val actions = database.notionActionDao().getActionsForMemory("mem_ask_1")
        assertEquals(1, actions.size)
        assertEquals("CONFIRM_PENDING", actions[0].status)
        assertEquals("page_target_123", actions[0].targetId)
    }

    @Test
    fun policyAskEachTimeNotificationDeniedDowngradesToNeedsAttention() = runTest {
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)

        // Deny notification permission
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val memory = MemoryItem(
            id = "mem_denied_1",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 2000L,
            rawText = "Note with denied notification",
            title = "Denied Note"
        )

        val handled = coordinator.process(memory, memory.rawText)

        assertFalse(handled)
        val actions = database.notionActionDao().getActionsForMemory("mem_denied_1")
        assertEquals(1, actions.size)
        assertEquals("NEEDS_ATTENTION", actions[0].status)
        assertTrue(actions[0].lastError!!.contains("通知未開啟或發送失敗"))
    }

    @Test
    fun policyAutoForSelectedTypesUploadsWhenEligible() = runTest {
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateAutoNotionSources(setOf(SourceType.SCREENSHOT))
        settingsRepository.updateAutoNotionEligibilityEpoch(1000L)

        val memory = MemoryItem(
            id = "mem_auto_eligible",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 2000L,
            createdAt = 2000L,
            rawText = "Auto upload note",
            title = "Eligible Note"
        )

        val handled = coordinator.process(memory, memory.rawText)

        assertTrue(handled)
        assertEquals(1, fakeNotionClient.createdPages.size)

        val actions = database.notionActionDao().getActionsForMemory("mem_auto_eligible")
        assertEquals(1, actions.size)
        assertEquals("CREATED", actions[0].status)
    }

    @Test
    fun policyAutoForSelectedTypesRejectsUnselectedSourceType() = runTest {
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateAutoNotionSources(setOf(SourceType.SCREENSHOT)) // Photo NOT permitted
        settingsRepository.updateAutoNotionEligibilityEpoch(1000L)

        val memory = MemoryItem(
            id = "mem_photo_unselected",
            sourceType = SourceType.PHOTO,
            capturedAt = 2000L,
            createdAt = 2000L,
            rawText = "Photo note text",
            title = "Photo Note"
        )

        val handled = coordinator.process(memory, memory.rawText)

        // Because source is not in autoNotionSources, auto upload is blocked; requires confirmation
        assertEquals(0, fakeNotionClient.createdPages.size)
        val actions = database.notionActionDao().getActionsForMemory("mem_photo_unselected")
        assertEquals(1, actions.size)
        assertEquals("CONFIRM_PENDING", actions[0].status)
    }

    @Test
    fun historicalMemoryBeforeEpochNeverAutoUploaded() = runTest {
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateAutoNotionSources(setOf(SourceType.SCREENSHOT))
        settingsRepository.updateAutoNotionEligibilityEpoch(5000L) // Epoch is 5000L

        val memory = MemoryItem(
            id = "mem_historical",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 2000L, // Historical memory (before 5000L)
            createdAt = 2000L,
            rawText = "Old captured receipt",
            title = "Historical Note"
        )

        val handled = coordinator.process(memory, memory.rawText)

        assertEquals(0, fakeNotionClient.createdPages.size)
        val actions = database.notionActionDao().getActionsForMemory("mem_historical")
        assertEquals(1, actions.size)
        assertEquals("CONFIRM_PENDING", actions[0].status)
    }

    @Test
    fun reconcileInFlightStaleCreatingActions() = runTest {
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        val staleTime = System.currentTimeMillis() - 40_000L

        database.notionActionDao().insert(
            com.cayana.actions.notion.data.NotionActionEntity(
                id = "action_stale_creating",
                sourceMemoryId = "mem_stale",
                actionType = NotionDestination.ACTION_TYPE_CREATE,
                targetId = "page_target_123",
                targetTitle = "Default Page",
                targetType = "PAGE",
                status = "CREATING",
                title = "Stale Note",
                updatedAt = staleTime
            )
        )

        // Mock Notion search finding matching page
        fakeNotionClient.searchTargetsResultToReturn = Result.Success(
            com.cayana.actions.notion.client.NotionSearchResponse(
                targets = listOf(
                    com.cayana.actions.notion.client.NotionTarget(
                        id = "remote_found_page_id",
                        title = "Stale Note",
                        type = com.cayana.actions.notion.client.NotionTargetType.PAGE,
                        isSelectable = true
                    )
                )
            )
        )

        val reconciled = coordinator.reconcileInFlightActions(recoverMemories = false)

        assertEquals(1, reconciled)
        val action = database.notionActionDao().getActionById("action_stale_creating")
        assertEquals("CREATED", action!!.status)
        assertEquals("remote_found_page_id", action.destinationPageId)
    }
}
