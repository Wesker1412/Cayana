package com.cayana.calendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.receiver.ConfirmCalendarEventReceiver
import com.cayana.calendar.receiver.UndoCalendarEventReceiver
import com.cayana.calendar.writer.AndroidCalendarWriter
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.event.extractor.DeterministicEventExtractor
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarContentProvider
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarPipelineIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var calendarActionDao: CalendarActionDao
    private lateinit var fakeProvider: FakeCalendarContentProvider
    private lateinit var fakeHelper: FakeCalendarProviderHelper
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var calendarWriter: AndroidCalendarWriter
    private lateinit var coordinator: CalendarProcessingCoordinator

    private val zoneId = ZoneId.of("Asia/Taipei")
    private val fixedRefTime = ZonedDateTime.of(
        LocalDate.of(2026, 10, 5),
        java.time.LocalTime.of(10, 0),
        zoneId
    ).toInstant()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        fakeProvider = FakeCalendarContentProvider.register(context)
        fakeHelper = FakeCalendarProviderHelper(hasPermission = true, hasWritePermission = true)

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
        calendarWriter = AndroidCalendarWriter(context, fakeHelper, logger)

        coordinator = CalendarProcessingCoordinator(
            context = context,
            eventExtractor = DeterministicEventExtractor(),
            calendarWriter = calendarWriter,
            calendarProviderHelper = fakeHelper,
            calendarActionDao = calendarActionDao,
            settingsRepository = settingsRepository,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createMemory(id: String, text: String): MemoryItem {
        return MemoryItem(
            id = id,
            sourceType = SourceType.SCREENSHOT,
            createdAt = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis(),
            title = "Test Memory",
            rawText = text,
            normalizedText = text,
            sourceUri = "content://media/external/images/media/$id",
            processingState = ProcessingState.COMPLETED
        )
    }

    @Test
    fun highConfidenceAutoAddsEventAndPersistsAction() = runTest {
        val memory = createMemory("mem-killer", "XX Live\n10/18 19:30\n台北流行音樂中心")

        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertTrue(handled)

        // Verify Calendar Provider received the event
        assertEquals(1, fakeProvider.events.size)
        val eventId = fakeProvider.events.keys.first()
        val eventValues = fakeProvider.events[eventId]!!
        assertEquals("XX Live", eventValues.getAsString(android.provider.CalendarContract.Events.TITLE))
        assertEquals("台北流行音樂中心", eventValues.getAsString(android.provider.CalendarContract.Events.EVENT_LOCATION))

        // Verify Room CalendarActionEntity persisted with status = CREATED
        val action = calendarActionDao.getAction(memory.id, "AUTO_CREATED")
        assertNotNull(action)
        assertEquals("CREATED", action!!.status)
        assertEquals(eventId, action.calendarEventId)
    }

    @Test
    fun dedupPreventsSecondEventOnRerun() = runTest {
        val memory = createMemory("mem-dedup", "XX Live\n10/18 19:30\n台北流行音樂中心")

        // First run
        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertEquals(1, fakeProvider.events.size)

        // Second run with same memory
        val rerunHandled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertTrue(rerunHandled)

        // Must still have exactly 1 event
        assertEquals(1, fakeProvider.events.size)
    }

    @Test
    fun businessHoursLowConfidenceZeroCalendarEvents() = runTest {
        val text = """
            營業時間
            週一至週五 09:00–18:00
            週六 10:00–17:00
        """.trimIndent()
        val memory = createMemory("mem-business", text)

        val handled = coordinator.process(memory, text, fixedRefTime, zoneId)
        // LOW confidence -> returns false, memory preserved without calendar action
        assertFalse(handled)
        assertEquals(0, fakeProvider.events.size)
        val actions = calendarActionDao.getActionsForMemory(memory.id)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun mediumConfidenceRequiresConfirmationBeforeWriting() = runTest {
        // Multi-event text yields MEDIUM confidence
        val text = """
            2026巡迴展覽
            台北場 10/18 19:00
            高雄場 10/19 19:00
        """.trimIndent()
        val memory = createMemory("mem-multi", text)

        val handled = coordinator.process(memory, text, fixedRefTime, zoneId)
        assertTrue(handled)

        // Medium confidence must NOT write to calendar immediately
        assertEquals(0, fakeProvider.events.size)

        // Must record pending action
        val action = calendarActionDao.getAction(memory.id, "CONFIRM_PENDING")
        assertNotNull(action)
        assertEquals("PENDING", action!!.status)
        assertNull(action.calendarEventId)

        // Trigger Confirm receiver with DECISION_CONFIRM
        val confirmIntent = android.content.Intent().apply {
            putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_CONFIRM)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_MEMORY_ID, memory.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_CALENDAR_ID, 1L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_TITLE, action.title)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_START_AT, action.startAt ?: 0L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_END_AT, action.endAt ?: 0L)
        }
        val receiver = ConfirmCalendarEventReceiver(calendarWriter, calendarActionDao)
        receiver.onReceive(context, confirmIntent)

        // Wait for coroutine to complete
        var attempts = 0
        while (calendarActionDao.getById(action.id)?.status != "CREATED" && attempts < 40) {
            Thread.sleep(50)
            attempts++
        }

        // Now event is created in calendar and action updated to CREATED
        assertEquals(1, fakeProvider.events.size)
        val updatedAction = calendarActionDao.getById(action.id)
        assertEquals("CREATED", updatedAction?.status)
    }

    @Test
    fun writeFailurePreservesMemory() = runTest {
        fakeHelper.hasWritePermission = false
        val memory = createMemory("mem-no-perm", "XX Live\n10/18 19:30\n台北流行音樂中心")

        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        // Handled is false because write permission is missing; memory remains saved
        assertFalse(handled)
        assertEquals(0, fakeProvider.events.size)
    }

    @Test
    fun undoRemovesCalendarEventAndUpdatesStatus() = runTest {
        val memory = createMemory("mem-undo", "XX Live\n10/18 19:30\n台北流行音樂中心")
        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)

        assertEquals(1, fakeProvider.events.size)
        val action = calendarActionDao.getAction(memory.id, "AUTO_CREATED")!!
        val eventId = action.calendarEventId!!

        // Trigger Undo receiver
        val undoIntent = android.content.Intent().apply {
            putExtra(UndoCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(UndoCalendarEventReceiver.EXTRA_EVENT_ID, eventId)
        }
        val receiver = UndoCalendarEventReceiver(calendarWriter, calendarActionDao)
        receiver.onReceive(context, undoIntent)

        // Wait for coroutine to complete
        var attempts = 0
        while (calendarActionDao.getById(action.id)?.status != "UNDONE" && attempts < 40) {
            Thread.sleep(50)
            attempts++
        }

        // Calendar event removed
        assertFalse(fakeProvider.events.containsKey(eventId))

        // CalendarAction status updated to UNDONE
        val updatedAction = calendarActionDao.getById(action.id)
        assertEquals("UNDONE", updatedAction?.status)
    }

    @Test
    fun notificationDeniedHighConfidenceDoesNotCreateCalendarEvent() = runTest {
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val memory = createMemory("mem-no-notif-high", "XX Live\n10/18 19:30\n台北流行音樂中心")
        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)

        assertFalse(handled)
        assertEquals(0, fakeProvider.events.size)
        assertTrue(calendarActionDao.getActionsForMemory(memory.id).isEmpty())
    }

    @Test
    fun notificationDeniedMediumConfidenceDoesNotCreatePendingAction() = runTest {
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val text = "2026巡迴展覽\n台北場 10/18 19:00\n高雄場 10/19 19:00"
        val memory = createMemory("mem-no-notif-med", text)
        val handled = coordinator.process(memory, text, fixedRefTime, zoneId)

        assertFalse(handled)
        assertEquals(0, fakeProvider.events.size)
        assertTrue(calendarActionDao.getActionsForMemory(memory.id).isEmpty())
    }

    @Test
    fun failedActionRetryDoesNotOrphanDuplicate() = runTest {
        val memory = createMemory("mem-retry", "XX Live\n10/18 19:30\n台北流行音樂中心")
        // Insert a pre-existing failed action
        val existingAction = com.cayana.calendar.data.CalendarActionEntity(
            id = "retry-action-1",
            memoryId = memory.id,
            calendarId = 1L,
            calendarEventId = null,
            actionType = CalendarProcessingCoordinator.ACTION_TYPE_AUTO_CREATED,
            createdAt = System.currentTimeMillis(),
            status = "FAILED",
            title = "XX Live",
            startAt = 0L,
            endAt = 0L
        )
        calendarActionDao.insert(existingAction)

        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertTrue(handled)

        // Exactly 1 event created in provider
        assertEquals(1, fakeProvider.events.size)
        val eventId = fakeProvider.events.keys.first()

        // Existing row was reused and updated to CREATED
        val actions = calendarActionDao.getActionsForMemory(memory.id)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertEquals("retry-action-1", action.id)
        assertEquals("CREATED", action.status)
        assertEquals(eventId, action.calendarEventId)
    }

    @Test
    fun externalCalendarSuccessRoomFinalUpdateFailure() = runTest {
        val memory = createMemory("mem-room-fail", "XX Live\n10/18 19:30\n台北流行音樂中心")

        // Subclass DAO with failing updateStatusAndEventId
        val failingDao = object : CalendarActionDao by calendarActionDao {
            override suspend fun updateStatusAndEventId(id: String, status: String, calendarEventId: Long?): Int {
                return 0 // Simulates DB failure or update 0 rows
            }
        }

        val testCoordinator = CalendarProcessingCoordinator(
            context = context,
            eventExtractor = DeterministicEventExtractor(),
            calendarWriter = calendarWriter,
            calendarProviderHelper = fakeHelper,
            calendarActionDao = failingDao,
            settingsRepository = settingsRepository,
            logger = DefaultCayanaLogger()
        )

        val handled = testCoordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse(handled)

        // Calendar event must have been compensated and deleted!
        assertEquals(0, fakeProvider.events.size)

        // Action status must be CREATING_ERROR
        val action = calendarActionDao.getAction(memory.id, CalendarProcessingCoordinator.ACTION_TYPE_AUTO_CREATED)
        assertNotNull(action)
        assertEquals("CREATING_ERROR", action!!.status)
    }

    @Test
    fun notificationPostFailureCompensatesCalendarEvent() = runTest {
        val memory = createMemory("mem-notif-fail", "XX Live\n10/18 19:30\n台北流行音樂中心")

        // Intercept writer creation to revoke notification permission right after calendar creation
        var createdEventId: Long? = null
        val interceptingWriter = object : com.cayana.calendar.writer.CalendarWriter by calendarWriter {
            override suspend fun createEvent(
                candidate: com.cayana.calendar.writer.ValidatedEvent
            ): com.cayana.calendar.writer.CalendarWriteResult {
                val res = calendarWriter.createEvent(candidate)
                if (res is com.cayana.calendar.writer.CalendarWriteResult.Success) {
                    createdEventId = res.calendarEventId
                    // Revoke notification permission right after calendar create
                    val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
                    shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                return res
            }
        }

        val testCoordinator = CalendarProcessingCoordinator(
            context = context,
            eventExtractor = DeterministicEventExtractor(),
            calendarWriter = interceptingWriter,
            calendarProviderHelper = fakeHelper,
            calendarActionDao = calendarActionDao,
            settingsRepository = settingsRepository,
            logger = DefaultCayanaLogger()
        )

        val handled = testCoordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse(handled)

        // The created event must have been compensated and deleted
        assertEquals(0, fakeProvider.events.size)

        // Action status must be FAILED
        val action = calendarActionDao.getAction(memory.id, CalendarProcessingCoordinator.ACTION_TYPE_AUTO_CREATED)
        assertNotNull(action)
        assertEquals("FAILED", action!!.status)
    }

    @Test
    fun undoPermissionRevokedFailsAndLeavesEvent() = runTest {
        val memory = createMemory("mem-undo-revoked", "XX Live\n10/18 19:30\n台北流行音樂中心")
        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertEquals(1, fakeProvider.events.size)

        val action = calendarActionDao.getAction(memory.id, "AUTO_CREATED")!!
        val eventId = action.calendarEventId!!

        // Revoke write permission before undo
        fakeHelper.hasWritePermission = false

        val undoIntent = android.content.Intent().apply {
            putExtra(UndoCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(UndoCalendarEventReceiver.EXTRA_EVENT_ID, eventId)
        }
        val receiver = UndoCalendarEventReceiver(calendarWriter, calendarActionDao)
        receiver.onReceive(context, undoIntent)

        var attempts = 0
        while (calendarActionDao.getById(action.id)?.status != "UNDO_FAILED" && attempts < 40) {
            Thread.sleep(50)
            attempts++
        }

        // Event must still be in Calendar (not deleted!)
        assertTrue(fakeProvider.events.containsKey(eventId))
        // Status must be UNDO_FAILED, never falsely marked UNDONE
        assertEquals("UNDO_FAILED", calendarActionDao.getById(action.id)?.status)
    }

    @Test
    fun undoProviderExceptionLeavesActionNotUndone() = runTest {
        val memory = createMemory("mem-undo-ex", "XX Live\n10/18 19:30\n台北流行音樂中心")
        coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertEquals(1, fakeProvider.events.size)

        val action = calendarActionDao.getAction(memory.id, "AUTO_CREATED")!!
        val eventId = action.calendarEventId!!

        // Provider throws on delete
        fakeProvider.shouldThrowOnDelete = true

        val undoIntent = android.content.Intent().apply {
            putExtra(UndoCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(UndoCalendarEventReceiver.EXTRA_EVENT_ID, eventId)
        }
        val receiver = UndoCalendarEventReceiver(calendarWriter, calendarActionDao)
        receiver.onReceive(context, undoIntent)

        var attempts = 0
        while (calendarActionDao.getById(action.id)?.status != "UNDO_FAILED" && attempts < 40) {
            Thread.sleep(50)
            attempts++
        }

        assertEquals("UNDO_FAILED", calendarActionDao.getById(action.id)?.status)
        fakeProvider.shouldThrowOnDelete = false
    }

    @Test
    fun confirmIdempotencyDoubleConfirmCreatesExactlyOneEvent() = runTest {
        val text = "2026巡迴展覽\n台北場 10/18 19:00\n高雄場 10/19 19:00"
        val memory = createMemory("mem-double-confirm", text)
        coordinator.process(memory, text, fixedRefTime, zoneId)

        val action = calendarActionDao.getAction(memory.id, "CONFIRM_PENDING")!!
        assertEquals("PENDING", action.status)

        val confirmIntent = android.content.Intent().apply {
            putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_CONFIRM)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_MEMORY_ID, memory.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_CALENDAR_ID, 1L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_TITLE, action.title)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_START_AT, action.startAt ?: 0L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_END_AT, action.endAt ?: 0L)
        }
        val receiver = ConfirmCalendarEventReceiver(calendarWriter, calendarActionDao)

        // Send two confirm broadcasts sequentially
        receiver.onReceive(context, confirmIntent)
        receiver.onReceive(context, confirmIntent)

        var attempts = 0
        while (calendarActionDao.getById(action.id)?.status != "CREATED" && attempts < 40) {
            Thread.sleep(50)
            attempts++
        }

        // Exactly one event created
        assertEquals(1, fakeProvider.events.size)
        assertEquals("CREATED", calendarActionDao.getById(action.id)?.status)
    }

    @Test
    fun confirmIgnoreRaceResultsInOneTerminalDecision() = runTest {
        val text = "2026巡迴展覽\n台北場 10/18 19:00\n高雄場 10/19 19:00"
        val memory = createMemory("mem-race", text)
        coordinator.process(memory, text, fixedRefTime, zoneId)

        val action = calendarActionDao.getAction(memory.id, "CONFIRM_PENDING")!!
        assertEquals("PENDING", action.status)

        val confirmIntent = android.content.Intent().apply {
            putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_CONFIRM)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_MEMORY_ID, memory.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_CALENDAR_ID, 1L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_TITLE, action.title)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_START_AT, action.startAt ?: 0L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_END_AT, action.endAt ?: 0L)
        }
        val ignoreIntent = android.content.Intent().apply {
            putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_IGNORE)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_MEMORY_ID, memory.id)
        }
        val receiver = ConfirmCalendarEventReceiver(calendarWriter, calendarActionDao)

        receiver.onReceive(context, confirmIntent)
        receiver.onReceive(context, ignoreIntent)

        var attempts = 0
        var finalStatus = calendarActionDao.getById(action.id)?.status
        while (finalStatus != "CREATED" && finalStatus != "IGNORED" && attempts < 40) {
            Thread.sleep(50)
            attempts++
            finalStatus = calendarActionDao.getById(action.id)?.status
        }

        assertTrue(finalStatus == "CREATED" || finalStatus == "IGNORED")
        if (finalStatus == "CREATED") {
            assertEquals(1, fakeProvider.events.size)
        } else {
            assertEquals(0, fakeProvider.events.size)
        }
    }

    @Test
    fun allDayEventWritesUtcMidnight() = runTest {
        // Date without time: 10/25
        val text = "跨年博覽會\n10/25\n台北小巨蛋"
        val memory = createMemory("mem-allday", text)

        val handled = coordinator.process(memory, text, fixedRefTime, zoneId)
        assertTrue(handled)

        // Capped at MEDIUM -> PENDING
        val action = calendarActionDao.getAction(memory.id, "CONFIRM_PENDING")!!
        assertEquals("PENDING", action.status)

        // Confirm
        val confirmIntent = android.content.Intent().apply {
            putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_CONFIRM)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_MEMORY_ID, memory.id)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_CALENDAR_ID, 1L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_TITLE, action.title)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_START_AT, action.startAt ?: 0L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_END_AT, action.endAt ?: 0L)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_IS_ALL_DAY, true)
        }
        val receiver = ConfirmCalendarEventReceiver(calendarWriter, calendarActionDao)
        receiver.onReceive(context, confirmIntent)

        var attempts = 0
        while (calendarActionDao.getById(action.id)?.status != "CREATED" && attempts < 40) {
            Thread.sleep(50)
            attempts++
        }

        assertEquals(1, fakeProvider.events.size)
        val eventValues = fakeProvider.events.values.first()
        assertEquals(1, eventValues.getAsInteger(android.provider.CalendarContract.Events.ALL_DAY))
        assertEquals("UTC", eventValues.getAsString(android.provider.CalendarContract.Events.EVENT_TIMEZONE))

        val startMs = eventValues.getAsLong(android.provider.CalendarContract.Events.DTSTART)!!
        val endMs = eventValues.getAsLong(android.provider.CalendarContract.Events.DTEND)!!
        val startZdt = java.time.Instant.ofEpochMilli(startMs).atZone(java.time.ZoneOffset.UTC)
        val endZdt = java.time.Instant.ofEpochMilli(endMs).atZone(java.time.ZoneOffset.UTC)

        assertEquals(java.time.LocalTime.MIDNIGHT, startZdt.toLocalTime())
        assertEquals(java.time.LocalTime.MIDNIGHT, endZdt.toLocalTime())
        assertEquals(1, java.time.temporal.ChronoUnit.DAYS.between(startZdt.toLocalDate(), endZdt.toLocalDate()))
    }

    @Test
    fun notificationAppDisabledHighConfidenceCreatesZeroEvents() = runTest {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        shadowNotificationManager.setNotificationsEnabled(false)

        val memory = createMemory("mem-app-notif-disabled", "XX Live\n10/18 19:30\n台北流行音樂中心")
        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)

        assertFalse(handled)
        assertEquals(0, fakeProvider.events.size)
        assertTrue(calendarActionDao.getActionsForMemory(memory.id).isEmpty())
    }

    @Test
    fun notificationChannelBlockedHighConfidenceCreatesZeroEvents() = runTest {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val blockedChannel = android.app.NotificationChannel(
            com.cayana.core.notification.NotificationHelper.CHANNEL_ID,
            com.cayana.core.notification.NotificationHelper.CHANNEL_NAME,
            android.app.NotificationManager.IMPORTANCE_NONE
        )
        notificationManager.createNotificationChannel(blockedChannel)

        val memory = createMemory("mem-channel-blocked-high", "XX Live\n10/18 19:30\n台北流行音樂中心")
        val handled = coordinator.process(memory, memory.rawText, fixedRefTime, zoneId)

        assertFalse(handled)
        assertEquals(0, fakeProvider.events.size)
        assertTrue(calendarActionDao.getActionsForMemory(memory.id).isEmpty())
    }

    @Test
    fun notificationChannelBlockedMediumConfidenceCreatesZeroPendingActions() = runTest {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val blockedChannel = android.app.NotificationChannel(
            com.cayana.core.notification.NotificationHelper.CHANNEL_ID,
            com.cayana.core.notification.NotificationHelper.CHANNEL_NAME,
            android.app.NotificationManager.IMPORTANCE_NONE
        )
        notificationManager.createNotificationChannel(blockedChannel)

        val text = "2026巡迴展覽\n台北場 10/18 19:00\n高雄場 10/19 19:00"
        val memory = createMemory("mem-channel-blocked-med", text)
        val handled = coordinator.process(memory, text, fixedRefTime, zoneId)

        assertFalse(handled)
        assertEquals(0, fakeProvider.events.size)
        assertTrue(calendarActionDao.getActionsForMemory(memory.id).isEmpty())
    }

    @Test
    fun timezonePreservedWhenSystemDefaultChanges() = runTest {
        // Original candidate is in Asia/Taipei
        val text = "2026巡迴展覽\n台北場 10/18 19:00\n高雄場 10/19 19:00"
        val memory = createMemory("mem-tz-preserve", text)
        val handled = coordinator.process(memory, text, fixedRefTime, zoneId)
        assertTrue(handled)

        val action = calendarActionDao.getAction(memory.id, "CONFIRM_PENDING")!!
        assertEquals("PENDING", action.status)
        assertEquals("Asia/Taipei", action.zoneId)

        val originalDefaultTz = java.util.TimeZone.getDefault()
        try {
            // Simulate device traveling to Europe/Prague before user taps confirm
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Prague"))

            val confirmIntent = android.content.Intent().apply {
                putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_CONFIRM)
                putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, action.id)
            }
            val receiver = ConfirmCalendarEventReceiver(calendarWriter, calendarActionDao)
            receiver.onReceive(context, confirmIntent)

            var attempts = 0
            while (calendarActionDao.getById(action.id)?.status != "CREATED" && attempts < 40) {
                Thread.sleep(50)
                attempts++
            }

            assertEquals(1, fakeProvider.events.size)
            val eventValues = fakeProvider.events.values.first()
            // Even though system default was Europe/Prague, the event was written with Asia/Taipei
            assertEquals("Asia/Taipei", eventValues.getAsString(android.provider.CalendarContract.Events.EVENT_TIMEZONE))
        } finally {
            java.util.TimeZone.setDefault(originalDefaultTz)
        }
    }

    @Test
    fun roomUpdateFailDeleteFailPersistsCompensationFailed() = runTest {
        val memory = createMemory("mem-comp-fail", "XX Live\n10/18 19:30\n台北流行音樂中心")

        fakeProvider.shouldThrowOnDelete = true

        val failingDao = object : CalendarActionDao by calendarActionDao {
            override suspend fun updateStatusAndEventId(id: String, status: String, calendarEventId: Long?): Int {
                if (status == "CREATED") {
                    return 0 // Simulates DB failure for CREATED
                }
                // Allow recording COMPENSATION_FAILED
                return calendarActionDao.updateStatusAndEventId(id, status, calendarEventId)
            }
        }

        val testCoordinator = CalendarProcessingCoordinator(
            context = context,
            eventExtractor = DeterministicEventExtractor(),
            calendarWriter = calendarWriter,
            calendarProviderHelper = fakeHelper,
            calendarActionDao = failingDao,
            settingsRepository = settingsRepository,
            logger = DefaultCayanaLogger()
        )

        val handled = testCoordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse(handled)

        // Event was not deleted because delete threw
        assertEquals(1, fakeProvider.events.size)
        val eventId = fakeProvider.events.keys.first()

        val action = calendarActionDao.getAction(memory.id, CalendarProcessingCoordinator.ACTION_TYPE_AUTO_CREATED)
        assertNotNull(action)
        assertEquals("COMPENSATION_FAILED", action!!.status)
        assertEquals(eventId, action.calendarEventId)

        fakeProvider.shouldThrowOnDelete = false
    }

    @Test
    fun notificationFailDeleteFailPersistsCompensationFailed() = runTest {
        val memory = createMemory("mem-notif-comp-fail", "XX Live\n10/18 19:30\n台北流行音樂中心")

        fakeProvider.shouldThrowOnDelete = true

        val interceptingWriter = object : com.cayana.calendar.writer.CalendarWriter by calendarWriter {
            override suspend fun createEvent(
                candidate: com.cayana.calendar.writer.ValidatedEvent
            ): com.cayana.calendar.writer.CalendarWriteResult {
                val res = calendarWriter.createEvent(candidate)
                if (res is com.cayana.calendar.writer.CalendarWriteResult.Success) {
                    val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
                    shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                return res
            }
        }

        val testCoordinator = CalendarProcessingCoordinator(
            context = context,
            eventExtractor = DeterministicEventExtractor(),
            calendarWriter = interceptingWriter,
            calendarProviderHelper = fakeHelper,
            calendarActionDao = calendarActionDao,
            settingsRepository = settingsRepository,
            logger = DefaultCayanaLogger()
        )

        val handled = testCoordinator.process(memory, memory.rawText, fixedRefTime, zoneId)
        assertFalse(handled)

        // Event remains because delete failed
        assertEquals(1, fakeProvider.events.size)
        val eventId = fakeProvider.events.keys.first()

        val action = calendarActionDao.getAction(memory.id, CalendarProcessingCoordinator.ACTION_TYPE_AUTO_CREATED)
        assertNotNull(action)
        assertEquals("COMPENSATION_FAILED", action!!.status)
        assertEquals(eventId, action.calendarEventId)

        fakeProvider.shouldThrowOnDelete = false
    }

    @Test
    fun processDeathRecoveryReconcilesOrphanEventWithoutDuplicate() = runTest {
        val memory = createMemory("mem-crash-recovery", "XX Live\n10/18 19:30\n台北流行音樂中心")
        val actionId = java.util.UUID.randomUUID().toString()

        // 1. Action was inserted with CREATING and no calendarEventId (simulating crash before Room update)
        val staleAction = com.cayana.calendar.data.CalendarActionEntity(
            id = actionId,
            memoryId = memory.id,
            calendarId = 1L,
            calendarEventId = null,
            actionType = CalendarProcessingCoordinator.ACTION_TYPE_AUTO_CREATED,
            createdAt = System.currentTimeMillis() - 60000,
            status = "CREATING",
            title = "XX Live",
            startAt = fixedRefTime.toEpochMilli(),
            endAt = fixedRefTime.plusSeconds(3600).toEpochMilli(),
            location = "台北流行音樂中心",
            isAllDay = false,
            zoneId = "Asia/Taipei"
        )
        calendarActionDao.insert(staleAction)

        // 2. Calendar Event was actually inserted into Calendar Provider before crash
        val values = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.CALENDAR_ID, 1L)
            put(android.provider.CalendarContract.Events.TITLE, "XX Live")
            put(android.provider.CalendarContract.Events.DTSTART, fixedRefTime.toEpochMilli())
            put(android.provider.CalendarContract.Events.DTEND, fixedRefTime.plusSeconds(3600).toEpochMilli())
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, "Asia/Taipei")
            put(android.provider.CalendarContract.Events.CUSTOM_APP_PACKAGE, "com.cayana")
            put(android.provider.CalendarContract.Events.CUSTOM_APP_URI, "cayana://calendar-action/$actionId")
        }
        val uri = context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
        assertNotNull(uri)
        val externalEventId = android.content.ContentUris.parseId(uri!!)

        assertEquals(1, fakeProvider.events.size)

        // 3. Coordinator runs reconciliation (e.g. at app start or during screenshot pipeline)
        val reconciledCount = coordinator.reconcilePendingCreatingActions()
        assertEquals(1, reconciledCount)

        // 4. Verify stale action was updated to CREATED and matched with externalEventId
        val reconciledAction = calendarActionDao.getById(actionId)
        assertNotNull(reconciledAction)
        assertEquals("CREATED", reconciledAction!!.status)
        assertEquals(externalEventId, reconciledAction.calendarEventId)

        // 5. Must NOT have created any duplicate events
        assertEquals(1, fakeProvider.events.size)
    }

    @Test
    fun processDeathRecoveryWhenNotificationDisabledCompensatesOrphanEvent() = runTest {
        val memory = createMemory("mem-crash-recovery-notif-off", "XX Live\n10/18 19:30\n台北流行音樂中心")
        val actionId = java.util.UUID.randomUUID().toString()

        val staleAction = com.cayana.calendar.data.CalendarActionEntity(
            id = actionId,
            memoryId = memory.id,
            calendarId = 1L,
            calendarEventId = null,
            actionType = CalendarProcessingCoordinator.ACTION_TYPE_AUTO_CREATED,
            createdAt = System.currentTimeMillis() - 60000,
            status = "CREATING",
            title = "XX Live",
            startAt = fixedRefTime.toEpochMilli(),
            endAt = fixedRefTime.plusSeconds(3600).toEpochMilli(),
            location = "台北流行音樂中心",
            isAllDay = false,
            zoneId = "Asia/Taipei"
        )
        calendarActionDao.insert(staleAction)

        val values = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.CALENDAR_ID, 1L)
            put(android.provider.CalendarContract.Events.TITLE, "XX Live")
            put(android.provider.CalendarContract.Events.DTSTART, fixedRefTime.toEpochMilli())
            put(android.provider.CalendarContract.Events.DTEND, fixedRefTime.plusSeconds(3600).toEpochMilli())
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, "Asia/Taipei")
            put(android.provider.CalendarContract.Events.CUSTOM_APP_PACKAGE, "com.cayana")
            put(android.provider.CalendarContract.Events.CUSTOM_APP_URI, "cayana://calendar-action/$actionId")
        }
        val uri = context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
        assertNotNull(uri)

        // Revoke notification permission before reconciliation
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val reconciledCount = coordinator.reconcilePendingCreatingActions()
        assertEquals(1, reconciledCount)

        // Event was compensated (deleted) because notification was disabled
        assertEquals(0, fakeProvider.events.size)

        val reconciledAction = calendarActionDao.getById(actionId)
        assertNotNull(reconciledAction)
        assertEquals("FAILED", reconciledAction!!.status)
        assertNull(reconciledAction.calendarEventId)
    }
}
