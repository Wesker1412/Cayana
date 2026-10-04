package com.cayana.calendar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.writer.CalendarWriter
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class UndoCalendarEventReceiver(
    private var injectedWriter: CalendarWriter? = null,
    private var injectedDao: CalendarActionDao? = null
) : BroadcastReceiver(), KoinComponent {

    private val writer: CalendarWriter by lazy { injectedWriter ?: get() }
    private val dao: CalendarActionDao by lazy { injectedDao ?: get() }

    companion object {
        private const val TAG = "UndoCalendarReceiver"
        const val EXTRA_ACTION_ID = "extra_action_id"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (eventId != -1L) {
                    writer.deleteEvent(eventId)
                }
                dao.updateStatus(actionId, "UNDONE")
                CayanaLogger.i(TAG, "Undo event successful: actionId=$actionId, eventId=$eventId")
            } catch (e: Exception) {
                CayanaLogger.w(TAG, "Exception during undo: ${e.message}")
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
