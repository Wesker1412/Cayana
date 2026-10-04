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
}
