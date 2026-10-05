package com.cayana.calendar

import android.content.Context
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.calendar.writer.CalendarLookupResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

        // Notification is safety prerequisite for auto-create and confirm
        if (!NotificationHelper.canPostCalendarActionNotification(context)) {
            logger.i(TAG, "Notification permission or channel unavailable; skipping calendar actions as safety prerequisite")
            return false
        }

        // Bounded reconciliation of any pending CREATING actions
        try {
            reconcilePendingCreatingActions()
        } catch (e: Exception) {
            logger.w(TAG, "Reconciliation failed during process: ${e.message}")
        }

        // Deduplication check: check if any calendar action already recorded for this memory
        val existingActions = try {
            calendarActionDao.getActionsForMemory(memoryItem.id)
        } catch (e: Exception) {
            emptyList()
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

                val existingAutoAction = existingActions.find { it.actionType == ACTION_TYPE_AUTO_CREATED }
                val actionId: String
                if (existingAutoAction != null) {
                    if (existingAutoAction.status == "CREATED" || existingAutoAction.status == "UNDONE") {
                        logger.i(TAG, "Auto-created action already terminal (${existingAutoAction.status}) for memory ${memoryItem.id}")
                        return true
                    }
                    val claimed = calendarActionDao.claimRetryAction(existingAutoAction.id)
                    if (claimed != 1) {
                        logger.i(TAG, "Another execution claimed retry for action ${existingAutoAction.id}")
                        return true
                    }
                    actionId = existingAutoAction.id
                } else {
                    actionId = UUID.randomUUID().toString()
                    val reservation = CalendarActionEntity(
                        id = actionId,
                        memoryId = memoryItem.id,
                        calendarId = calendarId,
                        calendarEventId = null,
                        actionType = ACTION_TYPE_AUTO_CREATED,
                        createdAt = System.currentTimeMillis(),
                        status = "CREATING",
                        title = title,
                        startAt = startAt.toEpochMilli(),
                        endAt = endAt.toEpochMilli(),
                        location = primaryCandidate.location,
                        isAllDay = primaryCandidate.isAllDay,
                        zoneId = zoneId.id
                    )
                    try {
                        calendarActionDao.insert(reservation)
                    } catch (e: Exception) {
                        logger.w(TAG, "Reservation insert failed due to unique conflict: ${e.message}")
                        return true
                    }
                }

                val validatedEvent = ValidatedEvent(
                    memoryId = memoryItem.id,
                    calendarId = calendarId,
                    title = title,
                    startAt = startAt,
                    endAt = endAt,
                    location = primaryCandidate.location,
                    isAllDay = primaryCandidate.isAllDay,
                    zoneId = zoneId,
                    actionId = actionId
                )

                when (val result = calendarWriter.createEvent(validatedEvent)) {
                    is CalendarWriteResult.Success -> {
                        val eventId = result.calendarEventId
                        try {
                            val updated = calendarActionDao.updateStatusAndEventId(actionId, "CREATED", eventId)
                            if (updated != 1) {
                                throw IllegalStateException("Failed to update status to CREATED for action $actionId")
                            }
                        } catch (e: Exception) {
                            logger.w(TAG, "Room final update failed after external event created, compensating: ${e.message}")
                            val delResult = try {
                                calendarWriter.deleteEvent(eventId)
                            } catch (compEx: Exception) {
                                logger.e(TAG, "Compensation event deletion failed: ${compEx.message}")
                                Result.failure(compEx)
                            }
                            if (delResult.isSuccess) {
                                try {
                                    calendarActionDao.updateStatus(actionId, "CREATING_ERROR")
                                } catch (_: Exception) {}
                            } else {
                                logger.e(TAG, "Compensation deletion failed after Room failure; persisting COMPENSATION_FAILED with eventId $eventId")
                                try {
                                    calendarActionDao.updateStatusAndEventId(actionId, "COMPENSATION_FAILED", eventId)
                                } catch (_: Exception) {}
                            }
                            return false
                        }

                        val formattedTime = NotificationHelper.formatEventDateTime(
                            startAt,
                            primaryCandidate.isAllDay,
                            zoneId
                        )
                        val posted = NotificationHelper.showCalendarAddedNotification(
                            context = context,
                            notificationId = memoryItem.id.hashCode(),
                            actionId = actionId,
                            calendarEventId = eventId,
                            title = title,
                            formattedDateTime = formattedTime
                        )
                        if (!posted) {
                            logger.w(TAG, "Failed to post Undo notification, compensating external event $eventId")
                            val delResult = try {
                                calendarWriter.deleteEvent(eventId)
                            } catch (compEx: Exception) {
                                logger.e(TAG, "Compensation event deletion failed after notification failure: ${compEx.message}")
                                Result.failure(compEx)
                            }
                            if (delResult.isSuccess) {
                                calendarActionDao.updateStatusAndEventId(actionId, "FAILED", null)
                            } else {
                                logger.e(TAG, "Compensation deletion failed after notification failure; persisting COMPENSATION_FAILED with eventId $eventId")
                                calendarActionDao.updateStatusAndEventId(actionId, "COMPENSATION_FAILED", eventId)
                            }
                            return false
                        }
                        logger.i(TAG, "Auto-created calendar event $eventId for memory ${memoryItem.id}")
                        true
                    }
                    is CalendarWriteResult.Failure -> {
                        try {
                            calendarActionDao.updateStatus(actionId, "FAILED")
                        } catch (_: Exception) {}

                        logger.w(TAG, "Failed to create calendar event for memory ${memoryItem.id}: ${result.reason}")
                        false
                    }
                }
            }

            ConfidenceLevel.MEDIUM -> {
                val startAt = primaryCandidate.startAt ?: return false
                val endAt = primaryCandidate.endAt ?: startAt.plusSeconds(3600)
                val title = primaryCandidate.title ?: return false

                val existingPending = existingActions.find {
                    it.actionType == ACTION_TYPE_CONFIRM_PENDING &&
                            (it.status == "PENDING" || it.status == "PROCESSING" || it.status == "CREATED" || it.status == "IGNORED" || it.status == "UNDONE")
                }
                if (existingPending != null) {
                    logger.i(TAG, "Confirm action already exists (${existingPending.status}) for memory ${memoryItem.id}")
                    return true
                }

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
                    endAt = endAt.toEpochMilli(),
                    location = primaryCandidate.location,
                    isAllDay = primaryCandidate.isAllDay,
                    zoneId = zoneId.id
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
                val posted = NotificationHelper.showCalendarConfirmNotification(
                    context = context,
                    notificationId = memoryItem.id.hashCode(),
                    actionId = actionId,
                    memoryId = memoryItem.id,
                    title = title,
                    formattedDateTime = formattedTime
                )
                if (!posted) {
                    logger.w(TAG, "Failed to post calendar confirm notification, updating status to FAILED")
                    try {
                        calendarActionDao.updateStatus(actionId, "FAILED")
                    } catch (_: Exception) {}
                    return false
                }
                logger.i(TAG, "Posted calendar confirm notification for memory ${memoryItem.id}")
                true
            }

            ConfidenceLevel.LOW -> {
                logger.d(TAG, "Candidate confidence is LOW for memory ${memoryItem.id}; saving memory only")
                false
            }
        }
    }

    suspend fun reconcileInFlightActions(): Int = withContext(Dispatchers.IO) {
        val inFlightActions = try {
            calendarActionDao.getInFlightActions(limit = 10)
        } catch (e: Exception) {
            logger.w(TAG, "Failed to query in-flight actions: ${e.message}")
            return@withContext 0
        }

        var reconciled = 0
        for (action in inFlightActions) {
            try {
                when (val lookup = calendarWriter.findEventByActionId(action.id)) {
                    is CalendarLookupResult.Unavailable -> {
                        logger.i(TAG, "Lookup unavailable for action ${action.id} (${lookup.cause?.message}); leaving status ${action.status} unchanged")
                        // Leave current state (CREATING or PROCESSING) unchanged; retry on a later bounded cycle
                    }
                    is CalendarLookupResult.Found -> {
                        val eventId = lookup.eventId
                        logger.i(TAG, "Reconciled existing calendar event $eventId for in-flight action ${action.id}")
                        if (NotificationHelper.canPostCalendarActionNotification(context)) {
                            val eventZone = action.zoneId?.let {
                                try { ZoneId.of(it) } catch (_: Exception) { ZoneId.systemDefault() }
                            } ?: ZoneId.systemDefault()
                            val formattedTime = NotificationHelper.formatEventDateTime(
                                action.startAt?.let { Instant.ofEpochMilli(it) },
                                action.isAllDay,
                                eventZone
                            )
                            val posted = NotificationHelper.showCalendarAddedNotification(
                                context = context,
                                notificationId = action.memoryId.hashCode(),
                                actionId = action.id,
                                calendarEventId = eventId,
                                title = action.title ?: "Event",
                                formattedDateTime = formattedTime
                            )
                            if (posted) {
                                calendarActionDao.updateStatusAndEventId(action.id, "CREATED", eventId)
                            } else {
                                logger.w(TAG, "Notification post failed during reconciliation for event $eventId; compensating")
                                compensateReconciledEvent(action.id, eventId)
                            }
                        } else {
                            logger.w(TAG, "Notification unavailable during reconciliation; compensating event $eventId")
                            compensateReconciledEvent(action.id, eventId)
                        }
                        reconciled++
                    }
                    is CalendarLookupResult.NotFound -> {
                        // Crash occurred before external insert.
                        // Room reservation has full payload: title, startAt, endAt, location, isAllDay, zoneId, calendarId, actionId.
                        // Recreate event with same actionId / CUSTOM_APP_URI.
                        logger.i(TAG, "In-flight action ${action.id} (${action.status}) confirmed NotFound in calendar; safely resuming creation")
                        resumeInFlightAction(action)
                        reconciled++
                    }
                }
            } catch (e: Exception) {
                logger.w(TAG, "Exception during reconciliation for action ${action.id}: ${e.message}")
            }
        }
        reconciled
    }

    private suspend fun compensateReconciledEvent(actionId: String, eventId: Long) {
        val delResult = try {
            calendarWriter.deleteEvent(eventId)
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (delResult.isSuccess) {
            calendarActionDao.updateStatusAndEventId(actionId, "FAILED", null)
        } else {
            logger.e(TAG, "Compensation deletion failed during reconciliation; persisting COMPENSATION_FAILED with eventId $eventId")
            calendarActionDao.updateStatusAndEventId(actionId, "COMPENSATION_FAILED", eventId)
        }
    }

    private suspend fun resumeInFlightAction(action: CalendarActionEntity) {
        if (!NotificationHelper.canPostCalendarActionNotification(context)) {
            logger.w(TAG, "Notification unavailable during resume for action ${action.id}; marking FAILED")
            calendarActionDao.updateStatus(action.id, "FAILED")
            return
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

        when (val result = calendarWriter.createEvent(validatedEvent)) {
            is CalendarWriteResult.Success -> {
                val eventId = result.calendarEventId
                val formattedTime = NotificationHelper.formatEventDateTime(
                    startInstant,
                    action.isAllDay,
                    originalZoneId
                )
                val posted = NotificationHelper.showCalendarAddedNotification(
                    context = context,
                    notificationId = action.memoryId.hashCode(),
                    actionId = action.id,
                    calendarEventId = eventId,
                    title = validatedEvent.title,
                    formattedDateTime = formattedTime
                )
                if (posted) {
                    calendarActionDao.updateStatusAndEventId(action.id, "CREATED", eventId)
                } else {
                    logger.w(TAG, "Notification post failed on resume for event $eventId; compensating")
                    compensateReconciledEvent(action.id, eventId)
                }
            }
            is CalendarWriteResult.Failure -> {
                logger.w(TAG, "Failed to create event on resume for action ${action.id}: ${result.reason}")
                calendarActionDao.updateStatus(action.id, "FAILED")
            }
        }
    }

    suspend fun reconcilePendingCreatingActions(): Int = reconcileInFlightActions()
}
