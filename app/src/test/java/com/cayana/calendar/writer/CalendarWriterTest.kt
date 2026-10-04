package com.cayana.calendar.writer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.test.FakeCalendarContentProvider
import com.cayana.test.FakeCalendarProviderHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarWriterTest {

    private lateinit var context: Context
    private lateinit var fakeProvider: FakeCalendarContentProvider
    private lateinit var fakeHelper: FakeCalendarProviderHelper
    private lateinit var writer: AndroidCalendarWriter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fakeProvider = FakeCalendarContentProvider.register(context)
        fakeHelper = FakeCalendarProviderHelper(hasPermission = true, hasWritePermission = true)
        writer = AndroidCalendarWriter(
            context = context,
            calendarProviderHelper = fakeHelper,
            logger = DefaultCayanaLogger()
        )
    }

    @Test
    fun insertEventSuccessfully() = runTest {
        val event = ValidatedEvent(
            memoryId = "mem-1",
            calendarId = 1L,
            title = "XX Live",
            startAt = Instant.ofEpochMilli(1729251000000L),
            endAt = Instant.ofEpochMilli(1729254600000L),
            location = "台北流行音樂中心",
            isAllDay = false,
            zoneId = ZoneId.of("Asia/Taipei")
        )

        val result = writer.createEvent(event)
        assertTrue(result is CalendarWriteResult.Success)
        val eventId = (result as CalendarWriteResult.Success).calendarEventId
        assertTrue(eventId > 0)
        assertTrue(fakeProvider.events.containsKey(eventId))

        val inserted = fakeProvider.events[eventId]!!
        assertEquals("XX Live", inserted.getAsString(android.provider.CalendarContract.Events.TITLE))
        assertEquals(1L, inserted.getAsLong(android.provider.CalendarContract.Events.CALENDAR_ID))
        assertEquals("Added by Cayana", inserted.getAsString(android.provider.CalendarContract.Events.DESCRIPTION))
    }

    @Test
    fun missingWritePermissionFails() = runTest {
        fakeHelper.hasWritePermission = false
        val event = ValidatedEvent(
            memoryId = "mem-1",
            calendarId = 1L,
            title = "XX Live",
            startAt = Instant.ofEpochMilli(1729251000000L),
            endAt = Instant.ofEpochMilli(1729254600000L)
        )

        val result = writer.createEvent(event)
        assertTrue(result is CalendarWriteResult.Failure)
        assertEquals("Missing WRITE_CALENDAR permission", (result as CalendarWriteResult.Failure).reason)
        assertEquals(0, fakeProvider.events.size)
    }

    @Test
    fun targetInvalidOrReadOnlyFailsWithoutFallback() = runTest {
        // Target calendar 999 does not exist
        val eventNonExistent = ValidatedEvent(
            memoryId = "mem-1",
            calendarId = 999L,
            title = "XX Live",
            startAt = Instant.ofEpochMilli(1729251000000L),
            endAt = Instant.ofEpochMilli(1729254600000L)
        )

        val result = writer.createEvent(eventNonExistent)
        assertTrue(result is CalendarWriteResult.Failure)
        assertEquals(0, fakeProvider.events.size)
    }

    @Test
    fun undoRemovesEventIdempotently() = runTest {
        val event = ValidatedEvent(
            memoryId = "mem-1",
            calendarId = 1L,
            title = "XX Live",
            startAt = Instant.ofEpochMilli(1729251000000L),
            endAt = Instant.ofEpochMilli(1729254600000L)
        )

        val createResult = writer.createEvent(event)
        val eventId = (createResult as CalendarWriteResult.Success).calendarEventId
        assertTrue(fakeProvider.events.containsKey(eventId))

        // First Undo: deletes event
        val deleteResult1 = writer.deleteEvent(eventId)
        assertTrue(deleteResult1.isSuccess)
        assertFalse(fakeProvider.events.containsKey(eventId))

        // Second Undo: idempotent, safe, does not throw
        val deleteResult2 = writer.deleteEvent(eventId)
        assertTrue(deleteResult2.isSuccess)
    }
}
