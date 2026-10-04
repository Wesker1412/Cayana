package com.cayana.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation that queries CalendarContract.Calendars to find writable calendars.
 * Strictly avoids logging or storing any account names or emails.
 */
class AndroidCalendarProviderHelper(
    private val context: Context,
    private val logger: CayanaLogger
) : CalendarProviderHelper {

    companion object {
        private const val TAG = "CalendarProvider"
    }

    override fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun hasWriteCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun isCalendarValidAndWritable(calendarId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) {
            return@withContext false
        }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection = "${CalendarContract.Calendars._ID} = ? AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(
            calendarId.toString(),
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
            cursor?.use {
                return@withContext it.moveToFirst()
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to validate calendar $calendarId: ${e.message}")
        }
        false
    }

    override suspend fun getWritableCalendars(): List<CalendarTarget> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) {
            logger.d(TAG, "Calendar permission not granted; returning empty calendar list")
            return@withContext emptyList()
        }

        val calendars = mutableListOf<CalendarTarget>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
                val nameIndex = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val primaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

                while (it.moveToNext()) {
                    val id = if (idIndex != -1) it.getString(idIndex) else null
                    val name = if (nameIndex != -1) it.getString(nameIndex) else null
                    val isPrimary = if (primaryIndex != -1) it.getInt(primaryIndex) == 1 else false

                    if (!id.isNullOrBlank()) {
                        val displayName = if (!name.isNullOrBlank()) name else "Calendar #$id"
                        calendars.add(
                            CalendarTarget(
                                id = id,
                                displayName = displayName,
                                isPrimary = isPrimary
                            )
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            logger.w(TAG, "SecurityException while querying Calendar Provider: ${e.message}")
        } catch (e: Exception) {
            logger.w(TAG, "Failed to query Calendar Provider: ${e.message}")
        }

        calendars
    }
}
