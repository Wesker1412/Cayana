package com.cayana.actions.tasks.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.tasks.data.TaskActionDao
import com.cayana.actions.tasks.destination.GoogleTasksDestination
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.Instant
import java.time.ZoneId

class ConfirmTaskActionReceiver(
    private var injectedDestination: GoogleTasksDestination? = null,
    private var injectedDao: TaskActionDao? = null
) : BroadcastReceiver(), KoinComponent {

    private val destination: GoogleTasksDestination by lazy { injectedDestination ?: get() }
    private val dao: TaskActionDao by lazy { injectedDao ?: get() }

    companion object {
        private const val TAG = "ConfirmTaskReceiver"
        const val EXTRA_DECISION = "extra_decision"
        const val DECISION_CONFIRM = "CONFIRM"
        const val DECISION_IGNORE = "IGNORE"

        const val EXTRA_ACTION_ID = "extra_action_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleIntent(context, intent)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    suspend fun handleIntent(context: Context, intent: Intent) {
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        try {
            if (decision == DECISION_IGNORE) {
                val affected = dao.claimIgnoreAction(actionId)
                CayanaLogger.i(TAG, "Task action ignored for actionId=$actionId (affected=$affected)")
                return
            }

            if (decision == DECISION_CONFIRM) {
                val action = dao.getById(actionId)
                if (action == null) {
                    CayanaLogger.w(TAG, "Task action not found for actionId=$actionId")
                    return
                }

                when (val result = destination.executeConfirmedTask(actionId)) {
                    is DestinationExecutionResult.Success -> {
                        val formattedDue = action.originalDueAt?.let {
                            NotificationHelper.formatEventDateTime(Instant.ofEpochMilli(it), false, ZoneId.systemDefault())
                        } ?: ""
                        NotificationHelper.showTaskAddedNotification(
                            context = context,
                            notificationId = if (notificationId != -1) notificationId else action.sourceMemoryId.hashCode() + 100,
                            actionId = actionId,
                            title = action.title,
                            formattedDue = formattedDue
                        )
                    }
                    is DestinationExecutionResult.NeedsAttention -> {
                        CayanaLogger.w(TAG, "Task confirmed by user but destination requires attention: ${result.reason}")
                    }
                    is DestinationExecutionResult.Failure -> {
                        CayanaLogger.w(TAG, "Task confirmed by user but destination failed: ${result.reason}")
                    }
                    DestinationExecutionResult.Skipped -> Unit
                }
            }
        } catch (e: Exception) {
            CayanaLogger.w(TAG, "Exception in handleIntent: ${e.javaClass.simpleName}")
        }
    }
}
