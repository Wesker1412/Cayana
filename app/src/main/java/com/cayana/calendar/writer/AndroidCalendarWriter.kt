package com.cayana.calendar.writer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.cayana.calendar.CalendarProviderHelper
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCalendarWriter(
    private val context: Context,
    private val calendarProviderHelper: CalendarProviderHelper,
    private val logger: CayanaLogger
) : CalendarWriter {

    companion object {
        private const val TAG = "CalendarWriter"
    }

    override suspend fun createEvent(candidate: ValidatedEvent): CalendarWriteResult = withContext(Dispatchers.IO) {
        if (!calendarProviderHelper.hasWriteCalendarPermission()) {
            logger.w(TAG, "WRITE_CALENDAR permission denied for memory ${candidate.memoryId}")
            return@withContext CalendarWriteResult.Failure("Missing WRITE_CALENDAR permission")
        }

        val isValid = calendarProviderHelper.isCalendarValidAndWritable(candidate.calendarId)
        if (!isValid) {
            logger.w(TAG, "Target calendar ${candidate.calendarId} is invalid or read-only")
            return@withContext CalendarWriteResult.Failure("Target calendar is invalid or read-only")
        }

        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, candidate.calendarId)
                put(CalendarContract.Events.TITLE, candidate.title)
                if (candidate.isAllDay) {
                    val startLocalDate = candidate.startAt.atZone(candidate.zoneId).toLocalDate()
                    val startUtcMs = startLocalDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    val endUtcMs = startLocalDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    put(CalendarContract.Events.DTSTART, startUtcMs)
                    put(CalendarContract.Events.DTEND, endUtcMs)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.ALL_DAY, 1)
                } else {
                    put(CalendarContract.Events.DTSTART, candidate.startAt.toEpochMilli())
                    put(CalendarContract.Events.DTEND, candidate.endAt.toEpochMilli())
                    put(CalendarContract.Events.EVENT_TIMEZONE, candidate.zoneId.id)
                    put(CalendarContract.Events.ALL_DAY, 0)
                }
                candidate.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                candidate.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                candidate.actionId?.let { actionId ->
                    put(CalendarContract.Events.CUSTOM_APP_PACKAGE, "com.cayana")
                    put(CalendarContract.Events.CUSTOM_APP_URI, "cayana://calendar-action/$actionId")
                }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()

            if (eventId != null) {
                logger.i(TAG, "Successfully created calendar event $eventId for memory ${candidate.memoryId}")
                CalendarWriteResult.Success(eventId)
            } else {
                logger.w(TAG, "Failed to obtain event ID after inserting for memory ${candidate.memoryId}")
                CalendarWriteResult.Failure("Failed to obtain event ID")
            }
        } catch (e: Exception) {
            logger.w(TAG, "Exception while writing calendar event: ${e.message}")
            CalendarWriteResult.Failure("Exception during calendar write", e)
        }
    }

    override suspend fun deleteEvent(eventId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        if (!calendarProviderHelper.hasWriteCalendarPermission()) {
            logger.w(TAG, "WRITE_CALENDAR permission denied while attempting to delete event $eventId")
            return@withContext Result.failure(SecurityException("Missing WRITE_CALENDAR permission"))
        }

        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rowsDeleted = context.contentResolver.delete(uri, null, null)
            logger.i(TAG, "Deleted calendar event $eventId (rows affected: $rowsDeleted)")
            Result.success(Unit) // rowsDeleted >= 0 is desired state achieved (idempotent)
        } catch (e: Exception) {
            logger.w(TAG, "Exception while deleting calendar event $eventId: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun findEventByActionId(actionId: String): CalendarLookupResult = withContext(Dispatchers.IO) {
        if (!calendarProviderHelper.hasCalendarPermission() && !calendarProviderHelper.hasWriteCalendarPermission()) {
            logger.w(TAG, "Missing calendar permission to find event by actionId $actionId")
            return@withContext CalendarLookupResult.Unavailable(
                SecurityException("Missing calendar permissions")
            )
        }

        try {
            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(CalendarContract.Events._ID)
            val selection = "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?"
            val selectionArgs = arrayOf("com.cayana", "cayana://calendar-action/$actionId")

            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?: return@withContext CalendarLookupResult.Unavailable(
                    IllegalStateException("ContentResolver query returned null cursor")
                )

            cursor.use {
                if (it.moveToFirst()) {
                    val idCol = it.getColumnIndex(CalendarContract.Events._ID)
                    if (idCol >= 0) {
                        val eventId = it.getLong(idCol)
                        logger.i(TAG, "Found calendar event $eventId for actionId $actionId")
                        return@withContext CalendarLookupResult.Found(eventId)
                    }
                }
            }
            CalendarLookupResult.NotFound
        } catch (e: Exception) {
            logger.w(TAG, "Exception querying event by actionId $actionId: ${e.message}")
            CalendarLookupResult.Unavailable(e)
        }
    }
}
