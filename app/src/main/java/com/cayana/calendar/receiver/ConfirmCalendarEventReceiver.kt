package com.cayana.calendar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.writer.CalendarWriteResult
import com.cayana.calendar.writer.CalendarWriter
import com.cayana.calendar.writer.ValidatedEvent
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.Instant
import java.time.ZoneId

class ConfirmCalendarEventReceiver(
    private var injectedWriter: CalendarWriter? = null,
    private var injectedDao: CalendarActionDao? = null
) : BroadcastReceiver(), KoinComponent {

    private val writer: CalendarWriter by lazy { injectedWriter ?: get() }
    private val dao: CalendarActionDao by lazy { injectedDao ?: get() }

    companion object {
        private const val TAG = "ConfirmCalendarReceiver"
        const val EXTRA_DECISION = "extra_decision"
        const val DECISION_CONFIRM = "CONFIRM"
        const val DECISION_IGNORE = "IGNORE"

        const val EXTRA_ACTION_ID = "extra_action_id"
        const val EXTRA_MEMORY_ID = "extra_memory_id"
        const val EXTRA_CALENDAR_ID = "extra_calendar_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_START_AT = "extra_start_at"
        const val EXTRA_END_AT = "extra_end_at"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_IS_ALL_DAY = "extra_is_all_day"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (decision == DECISION_IGNORE) {
                    val affected = dao.ignorePendingAction(actionId)
                    if (affected == 1) {
                        CayanaLogger.i(TAG, "Event ignored for actionId=$actionId")
                    } else {
                        CayanaLogger.d(TAG, "Ignore skipped, action not PENDING for actionId=$actionId")
                    }
                    return@launch
                }

                if (decision == DECISION_CONFIRM) {
                    val claimed = dao.claimPendingAction(actionId)
                    if (claimed != 1) {
                        CayanaLogger.i(TAG, "Confirm action already claimed or decided for actionId=$actionId")
                        return@launch
                    }

                    val memoryId = intent.getStringExtra(EXTRA_MEMORY_ID) ?: return@launch
                    val calendarId = intent.getLongExtra(EXTRA_CALENDAR_ID, -1L)
                    if (calendarId == -1L) {
                        dao.updateStatus(actionId, "FAILED")
                        return@launch
                    }
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: return@launch
                    val startAt = intent.getLongExtra(EXTRA_START_AT, 0L)
                    val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
                    val location = intent.getStringExtra(EXTRA_LOCATION)
                    val isAllDay = intent.getBooleanExtra(EXTRA_IS_ALL_DAY, false)

                    val validatedEvent = ValidatedEvent(
                        memoryId = memoryId,
                        calendarId = calendarId,
                        title = title,
                        startAt = Instant.ofEpochMilli(startAt),
                        endAt = Instant.ofEpochMilli(endAt),
                        location = location,
                        isAllDay = isAllDay,
                        zoneId = ZoneId.systemDefault()
                    )

                    when (val result = writer.createEvent(validatedEvent)) {
                        is CalendarWriteResult.Success -> {
                            try {
                                val updated = dao.updateStatusAndEventId(actionId, "CREATED", result.calendarEventId)
                                if (updated != 1) {
                                    throw IllegalStateException("Failed to update action $actionId to CREATED")
                                }
                            } catch (e: Exception) {
                                CayanaLogger.w(TAG, "Room update failed after calendar create, compensating: ${e.message}")
                                writer.deleteEvent(result.calendarEventId)
                                try {
                                    dao.updateStatus(actionId, "FAILED")
                                } catch (_: Exception) {}
                                return@launch
                            }

                            val formattedTime = NotificationHelper.formatEventDateTime(
                                validatedEvent.startAt,
                                validatedEvent.isAllDay,
                                validatedEvent.zoneId
                            )
                            val posted = NotificationHelper.showCalendarAddedNotification(
                                context = context,
                                notificationId = if (notificationId != -1) notificationId else memoryId.hashCode(),
                                actionId = actionId,
                                calendarEventId = result.calendarEventId,
                                title = title,
                                formattedDateTime = formattedTime
                            )
                            if (!posted) {
                                CayanaLogger.w(TAG, "Failed to post Undo notification after confirm, compensating event ${result.calendarEventId}")
                                writer.deleteEvent(result.calendarEventId)
                                try {
                                    dao.updateStatusAndEventId(actionId, "FAILED", null)
                                } catch (_: Exception) {}
                                return@launch
                            }
                            CayanaLogger.i(TAG, "Event confirmed & created for actionId=$actionId, eventId=${result.calendarEventId}")
                        }
                        is CalendarWriteResult.Failure -> {
                            dao.updateStatus(actionId, "FAILED")
                            CayanaLogger.w(TAG, "Failed to create event on confirm: ${result.reason}")
                        }
                    }
                }
            } catch (e: Exception) {
                CayanaLogger.w(TAG, "Exception during confirm: ${e.message}")
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
