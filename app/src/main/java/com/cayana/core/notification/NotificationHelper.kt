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
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.model.MemoryItem

/**
 * Handles posting minimalist memory capture notifications ("已記住").
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
                description = "Notifications when new memories are preserved by Cayana"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun showMemoryIngestedNotification(context: Context, item: MemoryItem) {
        if (!hasNotificationPermission(context)) {
            // Contextual permission: if denied, quietly skip notification without error
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

        // Build brief preview snippet
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
        } catch (e: SecurityException) {
            CayanaLogger.w("NotificationHelper", "SecurityException posting notification: ${e.message}")
        }
    }
}
