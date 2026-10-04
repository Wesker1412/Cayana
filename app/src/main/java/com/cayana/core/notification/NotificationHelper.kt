package com.cayana.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cayana.MainActivity
import com.cayana.R
import com.cayana.calendar.receiver.ConfirmCalendarEventReceiver
import com.cayana.calendar.receiver.UndoCalendarEventReceiver
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.model.MemoryItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Handles posting minimalist memory capture and calendar notifications.
 * Adheres strictly to contextual permission checks: if POST_NOTIFICATIONS is denied,
 * skips quietly without throwing exceptions or blocking memory creation.
 */
object NotificationHelper {

    const val CHANNEL_ID = "cayana_memory_channel"
    const val CHANNEL_NAME = "Cayana Memories"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when new memories or events are handled by Cayana"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun canPostCalendarActionNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }

        return true
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return canPostCalendarActionNotification(context)
    }

    fun formatEventDateTime(instant: Instant?, isAllDay: Boolean, zoneId: ZoneId): String {
        if (instant == null) return ""
        val zdt = instant.atZone(zoneId)
        return if (isAllDay) {
            zdt.format(DateTimeFormatter.ofPattern("M/d"))
        } else {
            zdt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        }
    }

    fun showMemoryIngestedNotification(context: Context, item: MemoryItem) {
        if (!canPostCalendarActionNotification(context)) {
            return
        }

        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val preview = if (!item.rawText.isNullOrBlank()) {
            val clean = item.rawText.replace("\n", " ").trim()
            if (clean.length > 50) clean.take(47) + "..." else clean
        } else {
            "螢幕截圖已儲存至記憶庫"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("已記住")
            .setContentText(preview)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
            CayanaLogger.i("NotificationHelper", "Posted memory notification id=${item.id}")
        } catch (e: SecurityException) {
            CayanaLogger.w("NotificationHelper", "SecurityException posting notification: ${e.message}")
        }
    }

    fun showCalendarAddedNotification(
        context: Context,
        notificationId: Int,
        actionId: String,
        calendarEventId: Long,
        title: String,
        formattedDateTime: String
    ): Boolean {
        if (!canPostCalendarActionNotification(context)) {
            return false
        }

        ensureChannel(context)

        val contentText = if (formattedDateTime.isNotBlank()) {
            "$title · $formattedDateTime"
        } else {
            title
        }

        val undoIntent = Intent(context, UndoCalendarEventReceiver::class.java).apply {
            putExtra(UndoCalendarEventReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(UndoCalendarEventReceiver.EXTRA_EVENT_ID, calendarEventId)
            putExtra(UndoCalendarEventReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val undoPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            undoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("已加入行事曆")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "復原", undoPendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            CayanaLogger.i("NotificationHelper", "Posted calendar added notification id=$notificationId")
            true
        } catch (e: Exception) {
            CayanaLogger.w("NotificationHelper", "Exception posting notification: ${e.message}")
            false
        }
    }

    fun showCalendarConfirmNotification(
        context: Context,
        notificationId: Int,
        actionId: String,
        memoryId: String,
        title: String,
        formattedDateTime: String
    ): Boolean {
        if (!canPostCalendarActionNotification(context)) {
            return false
        }

        ensureChannel(context)

        val contentText = if (formattedDateTime.isNotBlank()) {
            "$title · $formattedDateTime"
        } else {
            title
        }

        // Action 1: Confirm / Add
        val confirmIntent = Intent(context, ConfirmCalendarEventReceiver::class.java).apply {
            putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_CONFIRM)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val confirmPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 31 + 1,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Ignore
        val ignoreIntent = Intent(context, ConfirmCalendarEventReceiver::class.java).apply {
            putExtra(ConfirmCalendarEventReceiver.EXTRA_DECISION, ConfirmCalendarEventReceiver.DECISION_IGNORE)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(ConfirmCalendarEventReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val ignorePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 31 + 2,
            ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("發現可能的行程")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "加入", confirmPendingIntent)
            .addAction(0, "忽略", ignorePendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            CayanaLogger.i("NotificationHelper", "Posted calendar confirm notification id=$notificationId")
            true
        } catch (e: Exception) {
            CayanaLogger.w("NotificationHelper", "Exception posting notification: ${e.message}")
            false
        }
    }
}
