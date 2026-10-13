package com.cayana.core.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.cayana.memory.model.MemoryItem
import com.cayana.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationLockscreenPrivacyTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowApp = shadowOf(context.applicationContext as Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `showMemoryIngestedNotification sets VISIBILITY_PRIVATE and generic publicVersion`() {
        val secretOcr = "Secret Medical Record for Patient John Doe"
        val item = MemoryItem(
            id = "mem-1",
            sourceType = SourceType.SCREENSHOT,
            title = "Medical Record",
            rawText = secretOcr
        )

        NotificationHelper.showMemoryIngestedNotification(context, item)

        val shadowManager = shadowOf(notificationManager)
        val notifications = shadowManager.allNotifications
        assertEquals(1, notifications.size)

        val notification = notifications.first()
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)

        val publicVersion = notification.publicVersion
        assertNotNull("Public version notification must be set for lockscreen display", publicVersion)
        val publicTitle = publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val publicText = publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        assertEquals("已記住", publicTitle)
        assertEquals("已儲存一筆新記憶", publicText)
        assertFalse("Public version on lockscreen must never leak raw OCR text", publicText?.contains("Secret Medical") == true)
    }

    @Test
    fun `showCalendarAddedNotification sets VISIBILITY_PRIVATE and generic publicVersion`() {
        val eventTitle = "Confidential Oncology Appointment"
        NotificationHelper.showCalendarAddedNotification(
            context = context,
            notificationId = 101,
            actionId = "act-1",
            calendarEventId = 12345L,
            title = eventTitle,
            formattedDateTime = "10/15 14:00"
        )

        val shadowManager = shadowOf(notificationManager)
        val notifications = shadowManager.allNotifications
        val notification = notifications.first { it.extras.getString(Notification.EXTRA_TITLE) == "已加入行事曆" }

        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        val publicText = publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertEquals("已建立新的行事曆活動", publicText)
        assertFalse(publicText?.contains(eventTitle) == true)
    }

    @Test
    fun `showCalendarConfirmNotification sets VISIBILITY_PRIVATE and generic publicVersion`() {
        val eventTitle = "Salary Review Meeting"
        NotificationHelper.showCalendarConfirmNotification(
            context = context,
            notificationId = 102,
            actionId = "act-2",
            memoryId = "mem-2",
            title = eventTitle,
            formattedDateTime = "10/20 10:00"
        )

        val shadowManager = shadowOf(notificationManager)
        val notifications = shadowManager.allNotifications
        val notification = notifications.first { it.extras.getString(Notification.EXTRA_TITLE) == "發現可能的行程" }

        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        val publicText = publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertEquals("有新的行程需要確認", publicText)
        assertFalse(publicText?.contains(eventTitle) == true)
    }

    @Test
    fun `showTaskConfirmNotification sets VISIBILITY_PRIVATE and generic publicVersion`() {
        val taskTitle = "Pay overdue mortgage"
        NotificationHelper.showTaskConfirmNotification(
            context = context,
            notificationId = 103,
            actionId = "act-3",
            memoryId = "mem-3",
            title = taskTitle,
            formattedDue = "Tomorrow"
        )

        val shadowManager = shadowOf(notificationManager)
        val notifications = shadowManager.allNotifications
        val notification = notifications.first { it.extras.getString(Notification.EXTRA_TITLE) == "發現可能的待辦事項" }

        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        val publicText = publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertEquals("有新的待辦事項需要確認", publicText)
        assertFalse(publicText?.contains(taskTitle) == true)
    }

    @Test
    fun `showNotionConfirmNotification sets VISIBILITY_PRIVATE and generic publicVersion`() {
        val noteTitle = "Private Patent Draft"
        val snippet = "Secret formula details..."
        NotificationHelper.showNotionConfirmNotification(
            context = context,
            notificationId = 104,
            actionId = "act-4",
            memoryId = "mem-4",
            title = noteTitle,
            targetTitle = "Work Docs",
            snippet = snippet
        )

        val shadowManager = shadowOf(notificationManager)
        val notifications = shadowManager.allNotifications
        val notification = notifications.first { it.extras.getString(Notification.EXTRA_TITLE) == "儲存至 Notion 筆記？" }

        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        val publicText = publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertEquals("有新的 Notion 筆記需要確認", publicText)
        assertFalse(publicText?.contains(noteTitle) == true)
        assertFalse(publicText?.contains(snippet) == true)
    }
}
