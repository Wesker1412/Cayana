package com.cayana.calendar

import android.content.Context
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.calendar.writer.CalendarWriteResult
import com.cayana.calendar.writer.CalendarWriter
import com.cayana.calendar.writer.ValidatedEvent
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.notification.NotificationHelper
import com.cayana.event.extractor.EventExtractor
import com.cayana.event.model.ConfidenceLevel
import com.cayana.event.model.EventActionPolicy
import com.cayana.memory.model.MemoryItem
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class CalendarProcessingCoordinator(
    private val context: Context,
    private val eventExtractor: EventExtractor,
    private val calendarWriter: CalendarWriter,
    private val calendarProviderHelper: CalendarProviderHelper,
    private val calendarActionDao: CalendarActionDao,
    private val settingsRepository: SettingsRepository,
    private val logger: CayanaLogger
) {
    companion object {
        private const val TAG = "CalendarCoordinator"
        const val ACTION_TYPE_AUTO_CREATED = "AUTO_CREATED"
        const val ACTION_TYPE_CONFIRM_PENDING = "CONFIRM_PENDING"
    }

    /**
     * Processes OCR text from an ingested screenshot memory and determines whether
     * to automatically write to calendar, ask for confirmation, or skip.
     *
     * Returns true if calendar action or confirmation notification was posted,
     * false if it was skipped or fell back to default memory notification.
     */
    suspend fun process(
        memoryItem: MemoryItem,
        rawText: String?,
        referenceTime: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (rawText.isNullOrBlank()) {
            return false
        }

        val settings = try {
            settingsRepository.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "Failed to load user settings: ${e.message}")
            return false
        }

        if (!settings.autoCalendarEnabled) {
            logger.d(TAG, "Auto-calendar disabled in settings; skipping calendar processing")
            return false
        }

        val calendarId = settings.selectedCalendarId?.toLongOrNull()
        if (calendarId == null) {
            logger.d(TAG, "No valid calendar ID selected; skipping calendar processing")
            return false
        }

        // Check WRITE_CALENDAR permission
        if (!calendarProviderHelper.hasWriteCalendarPermission()) {
            logger.w(TAG, "Missing WRITE_CALENDAR permission for memory ${memoryItem.id}")
            return false
        }

        // Validate target calendar exists and is writable
        val isTargetValid = calendarProviderHelper.isCalendarValidAndWritable(calendarId)
        if (!isTargetValid) {
            logger.w(TAG, "Target calendar $calendarId is invalid or read-only; skipping calendar action")
            return false
        }

        // Deduplication check: check if any calendar action already recorded for this memory
        val existingActions = try {
            calendarActionDao.getActionsForMemory(memoryItem.id)
        } catch (e: Exception) {
            emptyList()
        }

        if (existingActions.isNotEmpty()) {
            val hasCreatedOrPending = existingActions.any {
                it.status == "CREATED" || it.status == "PENDING" || it.status == "IGNORED" || it.status == "UNDONE"
            }
            if (hasCreatedOrPending) {
                logger.i(TAG, "Calendar action already exists for memory ${memoryItem.id}; skipping duplicate processing")
                return true
            }
        }

        // Extract candidates deterministically
        val candidates = eventExtractor.extract(rawText, referenceTime, zoneId)
        if (candidates.isEmpty()) {
            return false
        }

        val primaryCandidate = candidates.first()
        val confidenceLevel = EventActionPolicy.evaluate(primaryCandidate, referenceTime)

        return when (confidenceLevel) {
            ConfidenceLevel.HIGH -> {
                val startAt = primaryCandidate.startAt ?: return false
                val endAt = primaryCandidate.endAt ?: startAt.plusSeconds(3600)
                val title = primaryCandidate.title ?: return false

                val validatedEvent = ValidatedEvent(
                    memoryId = memoryItem.id,
                    calendarId = calendarId,
                    title = title,
                    startAt = startAt,
                    endAt = endAt,
                    location = primaryCandidate.location,
                    isAllDay = primaryCandidate.isAllDay,
                    zoneId = zoneId
                )

                when (val result = calendarWriter.createEvent(validatedEvent)) {
                    is CalendarWriteResult.Success -> {
                        val actionId = UUID.randomUUID().toString()
                        val actionEntity = CalendarActionEntity(
                            id = actionId,
                            memoryId = memoryItem.id,
                            calendarId = calendarId,
                            calendarEventId = result.calendarEventId,
                            actionType = ACTION_TYPE_AUTO_CREATED,
                            createdAt = System.currentTimeMillis(),
                            status = "CREATED",
                            title = title,
                            startAt = startAt.toEpochMilli(),
                            endAt = endAt.toEpochMilli()
                        )
                        try {
                            calendarActionDao.insert(actionEntity)
                        } catch (e: Exception) {
                            logger.w(TAG, "Duplicate calendar action insert prevented: ${e.message}")
                        }

                        val formattedTime = NotificationHelper.formatEventDateTime(
                            startAt,
                            primaryCandidate.isAllDay,
                            zoneId
                        )
                        NotificationHelper.showCalendarAddedNotification(
                            context = context,
                            notificationId = memoryItem.id.hashCode(),
                            actionId = actionId,
                            calendarEventId = result.calendarEventId,
                            title = title,
                            formattedDateTime = formattedTime
                        )
                        logger.i(TAG, "Auto-created calendar event ${result.calendarEventId} for memory ${memoryItem.id}")
                        true
                    }
                    is CalendarWriteResult.Failure -> {
                        val actionId = UUID.randomUUID().toString()
                        val actionEntity = CalendarActionEntity(
                            id = actionId,
                            memoryId = memoryItem.id,
                            calendarId = calendarId,
                            calendarEventId = null,
                            actionType = ACTION_TYPE_AUTO_CREATED,
                            createdAt = System.currentTimeMillis(),
                            status = "FAILED",
                            title = title,
                            startAt = startAt.toEpochMilli(),
                            endAt = endAt.toEpochMilli()
                        )
                        try {
                            calendarActionDao.insert(actionEntity)
                        } catch (_: Exception) {}

                        logger.w(TAG, "Failed to create calendar event for memory ${memoryItem.id}: ${result.reason}")
                        false // Fall back to standard memory notification
                    }
                }
            }

            ConfidenceLevel.MEDIUM -> {
                val startAt = primaryCandidate.startAt ?: return false
                val endAt = primaryCandidate.endAt ?: startAt.plusSeconds(3600)
                val title = primaryCandidate.title ?: return false

                val actionId = UUID.randomUUID().toString()
                val actionEntity = CalendarActionEntity(
                    id = actionId,
                    memoryId = memoryItem.id,
                    calendarId = calendarId,
                    calendarEventId = null,
                    actionType = ACTION_TYPE_CONFIRM_PENDING,
                    createdAt = System.currentTimeMillis(),
                    status = "PENDING",
                    title = title,
                    startAt = startAt.toEpochMilli(),
                    endAt = endAt.toEpochMilli()
                )
                try {
                    calendarActionDao.insert(actionEntity)
                } catch (e: Exception) {
                    logger.w(TAG, "Duplicate confirm action insert prevented: ${e.message}")
                    return true
                }

                val formattedTime = NotificationHelper.formatEventDateTime(
                    startAt,
                    primaryCandidate.isAllDay,
                    zoneId
                )
                NotificationHelper.showCalendarConfirmNotification(
                    context = context,
                    notificationId = memoryItem.id.hashCode(),
                    actionId = actionId,
                    memoryId = memoryItem.id,
                    calendarId = calendarId,
                    title = title,
                    startAtMs = startAt.toEpochMilli(),
                    endAtMs = endAt.toEpochMilli(),
                    location = primaryCandidate.location,
                    isAllDay = primaryCandidate.isAllDay,
                    formattedDateTime = formattedTime
                )
                logger.i(TAG, "Posted calendar confirm notification for memory ${memoryItem.id}")
                true
            }

            ConfidenceLevel.LOW -> {
                logger.d(TAG, "Candidate confidence is LOW for memory ${memoryItem.id}; saving memory only")
                false
            }
        }
    }
}
