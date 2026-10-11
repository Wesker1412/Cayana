package com.cayana.actions

import android.content.Context
import android.provider.CalendarContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.actions.classifier.DeterministicActionClassifier
import com.cayana.actions.destination.CalendarDestinationAdapter
import com.cayana.actions.destination.DefaultDestinationRegistry
import com.cayana.actions.destination.NoteDestination
import com.cayana.actions.destination.TaskDestination
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.DestinationType
import com.cayana.actions.model.RoutingDecision
import com.cayana.actions.router.DefaultActionRouter
import com.cayana.actions.router.DefaultActionRoutingHistoryTracker
import com.cayana.calendar.CalendarProcessingCoordinator
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.writer.AndroidCalendarWriter
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.event.extractor.DeterministicEventExtractor
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarContentProvider
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.UserSettings
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
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionRouterTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var calendarActionDao: CalendarActionDao
    private lateinit var fakeProvider: FakeCalendarContentProvider
    private lateinit var fakeCalendarHelper: FakeCalendarProviderHelper
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var calendarWriter: AndroidCalendarWriter
    private lateinit var calendarCoordinator: CalendarProcessingCoordinator
    private lateinit var memoryRepository: FakeMemoryRepository

    private lateinit var classifier: DeterministicActionClassifier
    private lateinit var destinationRegistry: DefaultDestinationRegistry
    private lateinit var router: DefaultActionRouter
    private lateinit var fakeTaskDestination: FakeTaskDestination
    private lateinit var fakeNoteDestination: FakeNoteDestination

    private val zoneId = ZoneId.of("Asia/Taipei")
    private val fixedRefTime = ZonedDateTime.of(
        LocalDate.of(2026, 10, 5),
        LocalTime.of(10, 0),
        zoneId
    ).toInstant()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        fakeProvider = FakeCalendarContentProvider.register(context)
        fakeCalendarHelper = FakeCalendarProviderHelper(hasPermission = true, hasWritePermission = true)

        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        calendarActionDao = database.calendarActionDao()

        settingsRepository = InMemorySettingsRepository(
            initialSettings = UserSettings(
                autoCalendarEnabled = true,
                selectedCalendarId = "1",
                selectedCalendarName = "Personal"
            )
        )

        val logger = DefaultCayanaLogger()
        calendarWriter = AndroidCalendarWriter(context, fakeCalendarHelper, logger)

        calendarCoordinator = CalendarProcessingCoordinator(
            context = context,
            eventExtractor = DeterministicEventExtractor(),
            calendarWriter = calendarWriter,
            calendarProviderHelper = fakeCalendarHelper,
            calendarActionDao = calendarActionDao,
            settingsRepository = settingsRepository,
            logger = logger
        )

        memoryRepository = FakeMemoryRepository()

        classifier = DeterministicActionClassifier(
            eventExtractor = DeterministicEventExtractor()
        )

        val calendarDestination = CalendarDestinationAdapter(
            coordinator = calendarCoordinator,
            calendarProviderHelper = fakeCalendarHelper,
            settingsRepository = settingsRepository
        )

        fakeTaskDestination = FakeTaskDestination()
        fakeNoteDestination = FakeNoteDestination()

        destinationRegistry = DefaultDestinationRegistry(
            destinations = listOf(
                calendarDestination,
                fakeTaskDestination,
                fakeNoteDestination
            )
        )

        val routingHistoryTracker = DefaultActionRoutingHistoryTracker(
            calendarActionDao = calendarActionDao
        )

        router = DefaultActionRouter(
            classifier = classifier,
            destinationRegistry = destinationRegistry,
            routingHistoryTracker = routingHistoryTracker,
            memoryRepository = memoryRepository,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createTestMemory(
        id: String = UUID.randomUUID().toString(),
        text: String,
        title: String? = null
    ): MemoryItem {
        return MemoryItem(
            id = id,
            sourceType = SourceType.SCREENSHOT,
            createdAt = fixedRefTime.toEpochMilli(),
            capturedAt = fixedRefTime.toEpochMilli(),
            title = title,
            rawText = text,
            normalizedText = text,
            sourceUri = "content://media/external/images/media/101"
        )
    }

    // 1. Calendar existing behavior preserved
    @Test
    fun calendarExistingBehaviorPreserved() = runTest {
        val memoryText = "五月天回到那一天演唱會\n日期：2026-10-18 19:30\n地點：台北小巨蛋"
        val memory = createTestMemory(text = memoryText, title = "五月天演唱會")
        memoryRepository.saveMemory(memory)

        val decisions = router.route(memory, memoryText, fixedRefTime, zoneId)
        val calendarDecision = decisions.filterIsInstance<RoutingDecision.RouteAuto>()
            .firstOrNull { it.destinationType == DestinationType.CALENDAR }

        assertNotNull("Expected RouteAuto decision for high confidence calendar event", calendarDecision)
        assertEquals(ActionCandidateType.CALENDAR_EVENT, calendarDecision!!.candidate.type)

        val executionResult = router.executeDecision(calendarDecision, memory)
        assertTrue("Execution should succeed via Calendar pipeline", executionResult is DestinationExecutionResult.Success)

        // Verify exactly-once calendar action saved in Room CalendarActionDao
        val actions = calendarActionDao.getActionsForMemory(memory.id)
        assertEquals(1, actions.size)
        assertEquals("CREATED", actions[0].status)
        assertEquals("五月天回到那一天演唱會", actions[0].title)

        // Verify Calendar provider wrote the event
        assertEquals(1, fakeProvider.events.size)
        val eventValues = fakeProvider.events.values.first()
        assertEquals("五月天回到那一天演唱會", eventValues.getAsString(CalendarContract.Events.TITLE))
    }

    // 2. TASK candidate classification
    @Test
    fun taskCandidateClassification() = runTest {
        val taskText = "TODO: 繳納本月份水費與電費帳單\n截止日期: 2026-10-25 18:00"
        val memory = createTestMemory(text = taskText, title = "帳單繳費")

        val candidates = classifier.classify(memory, taskText, fixedRefTime, zoneId)
        val taskCandidate = candidates.firstOrNull { it.type == ActionCandidateType.TASK }

        assertNotNull("Should classify TASK candidate", taskCandidate)
        assertEquals("繳納本月份水費與電費帳單", taskCandidate!!.title)
        assertNotNull("Due date should be parsed from deadline", taskCandidate.dueAt)
        assertTrue("Task confidence should be high", taskCandidate.confidence >= 0.80f)
        assertEquals(memory.id, taskCandidate.sourceMemoryId)
    }

    // 3. NOTE candidate classification
    @Test
    fun noteCandidateClassification() = runTest {
        val noteText = "會議記錄: 討論 2027 產品願景與架構規劃\n- 強化 Android 本機離線私隱\n- 實作通用 Action Router 模組"
        val memory = createTestMemory(text = noteText, title = "產品會議")

        val candidates = classifier.classify(memory, noteText, fixedRefTime, zoneId)
        val noteCandidate = candidates.firstOrNull { it.type == ActionCandidateType.NOTE }

        assertNotNull("Should classify NOTE candidate", noteCandidate)
        assertTrue("Title should contain meeting topic", noteCandidate!!.title.contains("討論 2027 產品願景"))
        assertTrue("Notes should contain bounded snippet", noteCandidate.notes?.contains("強化 Android") == true)
        assertTrue("Note confidence should be high", noteCandidate.confidence >= 0.80f)
        assertEquals(memory.id, noteCandidate.sourceMemoryId)
    }

    // 4. No destination → Memory only
    @Test
    fun noDestinationFallsBackToMemoryOnly() = runTest {
        fakeTaskDestination.configured = false
        fakeTaskDestination.authorized = false

        val taskText = "TODO: 買咖啡豆與鮮奶"
        val memory = createTestMemory(text = taskText)
        memoryRepository.saveMemory(memory)

        val decisions = router.route(memory, taskText, fixedRefTime, zoneId)
        val taskDecision = decisions.firstOrNull { it is RoutingDecision.MemoryOnly && it.candidate?.type == ActionCandidateType.TASK }

        assertNotNull("Unconfigured destination must result in MemoryOnly", taskDecision)
        val memoryOnly = taskDecision as RoutingDecision.MemoryOnly
        assertTrue(memoryOnly.reason.contains("not configured"))

        // Execution of MemoryOnly does not invoke any destination
        router.executeDecision(memoryOnly, memory)
        assertEquals(0, fakeTaskDestination.createdTasks.size)

        // Memory remains safely persisted in repository
        val saved = memoryRepository.getMemoryById(memory.id).first()
        assertNotNull(saved)
    }

    // 5. Invalid authorization → no transmission
    @Test
    fun invalidAuthorizationResultsInNeedsAttentionWithoutTransmission() = runTest {
        // Destination configured, but authorization has expired / missing
        fakeTaskDestination.configured = true
        fakeTaskDestination.authorized = false

        val taskText = "TODO: 寄送合約書正本"
        val memory = createTestMemory(text = taskText)
        memoryRepository.saveMemory(memory)

        val decisions = router.route(memory, taskText, fixedRefTime, zoneId)
        val needsAttention = decisions.filterIsInstance<RoutingDecision.NeedsAttention>()
            .firstOrNull { it.destinationType == DestinationType.TASK }

        assertNotNull("Invalid authorization must yield NeedsAttention", needsAttention)
        assertEquals(DestinationType.TASK, needsAttention!!.destinationType)

        // Execution must refuse transmission and retain memory locally
        val execResult = router.executeDecision(needsAttention, memory)
        assertTrue(execResult is DestinationExecutionResult.NeedsAttention)
        assertEquals(0, fakeTaskDestination.createdTasks.size)

        // Private memory remains locally preserved
        assertNotNull(memoryRepository.getMemoryById(memory.id).first())
    }

    // 6. Low confidence → no automatic action
    @Test
    fun lowConfidencePreventsAutomaticAction() = runTest {
        fakeTaskDestination.configured = true
        fakeTaskDestination.authorized = true

        // Ambiguous text with low confidence / uncertainty question
        val ambiguousText = "這週可能要看一下那個東西或是問問看？"
        val memory = createTestMemory(text = ambiguousText)

        val decisions = router.route(memory, ambiguousText, fixedRefTime, zoneId)

        // None of the decisions may be RouteAuto
        val autoDecisions = decisions.filterIsInstance<RoutingDecision.RouteAuto>()
        assertTrue("Low confidence or uncertain candidate must not trigger RouteAuto", autoDecisions.isEmpty())

        for (decision in decisions) {
            router.executeDecision(decision, memory)
        }
        assertEquals("Zero external tasks created for ambiguous candidate", 0, fakeTaskDestination.createdTasks.size)
    }

    // 7. Duplicate routing does not duplicate action
    @Test
    fun duplicateRoutingDoesNotDuplicateAction() = runTest {
        val memoryText = "五月天回到那一天演唱會\n日期：2026-10-18 19:30\n地點：台北小巨蛋"
        val memory = createTestMemory(text = memoryText, title = "五月天演唱會")
        memoryRepository.saveMemory(memory)

        // First routing execution
        val decisions1 = router.route(memory, memoryText, fixedRefTime, zoneId)
        val calendarDecision1 = decisions1.filterIsInstance<RoutingDecision.RouteAuto>().first()
        router.executeDecision(calendarDecision1, memory)

        assertEquals(1, fakeProvider.events.size)
        assertEquals(1, calendarActionDao.getActionsForMemory(memory.id).size)

        // Second routing attempt for the same memory
        val decisions2 = router.route(memory, memoryText, fixedRefTime, zoneId)
        val duplicateCalendarDecision = decisions2.filterIsInstance<RoutingDecision.MemoryOnly>()
            .firstOrNull { it.candidate?.type == ActionCandidateType.CALENDAR_EVENT }

        assertNotNull("Second routing attempt must yield MemoryOnly due to duplicate detection", duplicateCalendarDecision)
        assertTrue(duplicateCalendarDecision!!.reason.contains("Duplicate"))

        // Even if executeDecision is invoked, provider events must remain exactly 1
        router.executeDecision(duplicateCalendarDecision, memory)
        assertEquals("No duplicate event should ever be created", 1, fakeProvider.events.size)
    }

    // 8. Failure never deletes canonical Memory
    @Test
    fun destinationFailureNeverDeletesCanonicalMemory() = runTest {
        fakeTaskDestination.configured = true
        fakeTaskDestination.authorized = true
        fakeTaskDestination.shouldFail = true // Inject fatal destination failure

        val taskText = "TODO: 送出急件審核"
        val memory = createTestMemory(text = taskText, title = "急件審核")
        memoryRepository.saveMemory(memory)

        val decisions = router.route(memory, taskText, fixedRefTime, zoneId)
        val taskDecision = decisions.filterIsInstance<RoutingDecision.RouteAuto>().first()

        val result = router.executeDecision(taskDecision, memory)
        assertTrue("Execution should report Failure", result is DestinationExecutionResult.Failure)

        // Crucial verification: canonical memory in Room/Repository must NOT be deleted or rolled back!
        val preservedMemory = memoryRepository.getMemoryById(memory.id).first()
        assertNotNull("Canonical memory must strictly remain in repository after destination failure", preservedMemory)
        assertEquals("急件審核", preservedMemory!!.title)
        assertEquals(taskText, preservedMemory.rawText)
    }

    // 9. Boundary: candidate does not expose full raw memory private data
    @Test
    fun candidateEncapsulatesBoundedPayloadOnly() = runTest {
        fakeNoteDestination.configured = true
        fakeNoteDestination.authorized = true

        val confidentialText = "會議記錄: 內部財務報表機密討論\n詳細營業額數據...\n" + "A".repeat(1000)
        val memory = createTestMemory(
            id = "mem-secret-123",
            text = confidentialText,
            title = "財務會議"
        )

        val decisions = router.route(memory, confidentialText, fixedRefTime, zoneId)
        val noteDecision = decisions.filterIsInstance<RoutingDecision.RouteAuto>().first()
        router.executeDecision(noteDecision, memory)

        assertEquals(1, fakeNoteDestination.createdNotes.size)
        val transmittedCandidate = fakeNoteDestination.createdNotes[0]

        // Candidate must not dump raw 1000+ byte private body or source URI
        assertTrue("Candidate notes should be bounded", (transmittedCandidate.notes?.length ?: 0) <= 200)
        assertFalse("Candidate must not contain sourceUri", transmittedCandidate.metadata.containsKey("sourceUri"))
    }

    // Test fakes for destinations
    class FakeTaskDestination(
        var configured: Boolean = false,
        var authorized: Boolean = false,
        var shouldFail: Boolean = false
    ) : TaskDestination {
        val createdTasks = mutableListOf<ActionCandidate>()

        override suspend fun isConfigured(): Boolean = configured
        override suspend fun isAuthorized(): Boolean = authorized

        override suspend fun createTask(candidate: ActionCandidate): DestinationExecutionResult {
            if (!isAuthorized()) {
                return DestinationExecutionResult.NeedsAttention("Not authorized")
            }
            if (shouldFail) {
                throw IOException("Simulated network failure communicating with Task API")
            }
            createdTasks.add(candidate)
            return DestinationExecutionResult.Success(
                destinationReferenceId = "task-${createdTasks.size}",
                message = "Created task ${candidate.title}"
            )
        }
    }

    class FakeNoteDestination(
        var configured: Boolean = false,
        var authorized: Boolean = false,
        var shouldFail: Boolean = false
    ) : NoteDestination {
        val createdNotes = mutableListOf<ActionCandidate>()

        override suspend fun isConfigured(): Boolean = configured
        override suspend fun isAuthorized(): Boolean = authorized

        override suspend fun createNote(candidate: ActionCandidate): DestinationExecutionResult {
            if (!isAuthorized()) {
                return DestinationExecutionResult.NeedsAttention("Not authorized")
            }
            if (shouldFail) {
                throw IOException("Simulated network failure communicating with Note API")
            }
            createdNotes.add(candidate)
            return DestinationExecutionResult.Success(
                destinationReferenceId = "note-${createdNotes.size}",
                message = "Created note ${candidate.title}"
            )
        }
    }
}
