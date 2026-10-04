package com.cayana.test

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import org.robolectric.shadows.ShadowContentResolver

class FakeCalendarContentProvider : ContentProvider() {

    val events = mutableMapOf<Long, ContentValues>()
    private var nextEventId = 100L

    val calendars = mutableMapOf<Long, ContentValues>()

    init {
        // Default writable calendar with ID 1
        calendars[1L] = ContentValues().apply {
            put(CalendarContract.Calendars._ID, 1L)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Personal")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
            put(CalendarContract.Calendars.IS_PRIMARY, 1)
        }
        // Read-only calendar with ID 2
        calendars[2L] = ContentValues().apply {
            put(CalendarContract.Calendars._ID, 2L)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Holidays")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.IS_PRIMARY, 0)
        }
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val cv = ContentValues(values)
        val id = nextEventId++
        cv.put(CalendarContract.Events._ID, id)
        events[id] = cv
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val eventId = uri.lastPathSegment?.toLongOrNull()
        return if (eventId != null && events.containsKey(eventId)) {
            events.remove(eventId)
            1
        } else {
            0
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (uri.toString().startsWith(CalendarContract.Calendars.CONTENT_URI.toString())) {
            val proj = projection ?: arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.IS_PRIMARY
            )
            val cursor = MatrixCursor(proj)

            var list = calendars.values.toList()
            if (selection != null && selectionArgs != null) {
                if (selection.contains("${CalendarContract.Calendars._ID} = ?")) {
                    val targetId = selectionArgs[0].toLongOrNull()
                    list = list.filter { it.getAsLong(CalendarContract.Calendars._ID) == targetId }
                }
                if (selection.contains("${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?")) {
                    val minLevel = (if (selection.contains("${CalendarContract.Calendars._ID} = ?") && selectionArgs.size > 1) {
                        selectionArgs[1]
                    } else {
                        selectionArgs[0]
                    }).toIntOrNull() ?: 0
                    list = list.filter { (it.getAsInteger(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) ?: 0) >= minLevel }
                }
            }

            for (cv in list) {
                val row = cursor.newRow()
                for (col in proj) {
                    when (col) {
                        CalendarContract.Calendars._ID -> row.add(cv.getAsLong(col))
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME -> row.add(cv.getAsString(col))
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL -> row.add(cv.getAsInteger(col))
                        CalendarContract.Calendars.IS_PRIMARY -> row.add(cv.getAsInteger(col))
                        else -> row.add(null)
                    }
                }
            }
            return cursor
        }

        return MatrixCursor(arrayOf("_id"))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        fun register(context: Context): FakeCalendarContentProvider {
            val provider = FakeCalendarContentProvider()
            val info = ProviderInfo().apply {
                authority = CalendarContract.AUTHORITY
                grantUriPermissions = true
            }
            provider.attachInfo(context, info)
            ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
            return provider
        }
    }
}
