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

                    val action = dao.getById(actionId)
                    if (action == null) {
                        CayanaLogger.w(TAG, "Action not found for actionId=$actionId")
                        return@launch
                    }

                    val originalZoneId = action.zoneId?.let {
                        try { ZoneId.of(it) } catch (_: Exception) { ZoneId.systemDefault() }
                    } ?: ZoneId.systemDefault()

                    val startInstant = action.startAt?.let { Instant.ofEpochMilli(it) } ?: Instant.now()
                    val endInstant = action.endAt?.let { Instant.ofEpochMilli(it) } ?: startInstant.plusSeconds(3600)

                    val validatedEvent = ValidatedEvent(
                        memoryId = action.memoryId,
                        calendarId = action.calendarId,
                        title = action.title ?: "Event",
                        startAt = startInstant,
                        endAt = endInstant,
                        location = action.location,
                        isAllDay = action.isAllDay,
                        zoneId = originalZoneId,
                        actionId = action.id
                    )

                    when (val result = writer.createEvent(validatedEvent)) {
                        is CalendarWriteResult.Success -> {
                            val eventId = result.calendarEventId
                            try {
                                val updated = dao.updateStatusAndEventId(actionId, "CREATED", eventId)
                                if (updated != 1) {
                                    throw IllegalStateException("Failed to update action $actionId to CREATED")
                                }
                            } catch (e: Exception) {
                                CayanaLogger.w(TAG, "Room update failed after calendar create, compensating: ${e.message}")
                                val delResult = writer.deleteEvent(eventId)
                                if (delResult.isSuccess) {
                                    try { dao.updateStatusAndEventId(actionId, "PROCESSING", null) } catch (_: Exception) {}
                                } else {
                                    CayanaLogger.e(TAG, "Compensation deletion failed after Room failure; persisting COMPENSATION_FAILED with eventId $eventId")
                                    try { dao.updateStatusAndEventId(actionId, "COMPENSATION_FAILED", eventId) } catch (_: Exception) {}
                                }
                                return@launch
                            }

                            val formattedTime = NotificationHelper.formatEventDateTime(
                                validatedEvent.startAt,
                                validatedEvent.isAllDay,
                                validatedEvent.zoneId
                            )
                            val posted = NotificationHelper.showCalendarAddedNotification(
                                context = context,
                                notificationId = if (notificationId != -1) notificationId else action.memoryId.hashCode(),
                                actionId = actionId,
                                calendarEventId = eventId,
                                title = validatedEvent.title,
                                formattedDateTime = formattedTime
                            )
                            if (!posted) {
                                CayanaLogger.w(TAG, "Failed to post Undo notification after confirm, compensating event $eventId")
                                val delResult = writer.deleteEvent(eventId)
                                if (delResult.isSuccess) {
                                    dao.updateStatusAndEventId(actionId, "PROCESSING", null)
                                } else {
                                    CayanaLogger.e(TAG, "Compensation deletion failed after notification failure; persisting COMPENSATION_FAILED with eventId $eventId")
                                    dao.updateStatusAndEventId(actionId, "COMPENSATION_FAILED", eventId)
                                }
                                return@launch
                            }
                            CayanaLogger.i(TAG, "Event confirmed & created for actionId=$actionId, eventId=$eventId")
                        }
                        is CalendarWriteResult.Failure -> {
                            CayanaLogger.w(TAG, "Failed to create event on confirm: ${result.reason}; keeping PROCESSING for recovery")
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
