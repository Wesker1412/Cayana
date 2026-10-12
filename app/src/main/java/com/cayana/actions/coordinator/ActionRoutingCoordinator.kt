package com.cayana.actions.coordinator

import android.content.Context
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.RoutingDecision
import com.cayana.actions.router.ActionRouter
import com.cayana.actions.tasks.data.TaskActionDao
import com.cayana.actions.tasks.data.TaskActionEntity
import com.cayana.actions.tasks.destination.GoogleTasksDestination
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.notification.NotificationHelper
import com.cayana.memory.model.MemoryItem
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ActionRoutingCoordinator(
    private val context: Context,
    private val actionRouter: ActionRouter,
    private val taskActionDao: TaskActionDao,
    private val googleTasksDestination: GoogleTasksDestination,
    private val settingsRepository: SettingsRepository,
    private val logger: CayanaLogger
) {
    companion object {
        private const val TAG = "ActionRoutingCoord"
        const val ACTION_TYPE_CREATE = GoogleTasksDestination.ACTION_TYPE_CREATE
    }

    /**
     * Shared, non-blocking entry point called after Memory is already persisted.
     * Evaluates action candidates (excluding Calendar which has its own coordinator)
     * and executes automatic actions or posts user confirmation notifications.
     *
     * Returns true if a task action was executed or confirmation notification was posted.
     */
    suspend fun process(
        memoryItem: MemoryItem,
        rawText: String?,
        referenceTime: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean = withContext(Dispatchers.IO) {
        if (rawText.isNullOrBlank()) {
            return@withContext false
        }

        val settings = try {
            settingsRepository.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "Failed loading settings: ${e.javaClass.simpleName}")
            return@withContext false
        }

        // Bounded reconciliation of any pending CREATING actions
        try {
            reconcileInFlightActions()
        } catch (e: Exception) {
            logger.w(TAG, "Reconciling in-flight actions error: ${e.javaClass.simpleName}")
        }

        val decisions = try {
            actionRouter.route(memoryItem, rawText, referenceTime, zoneId)
        } catch (e: Exception) {
            logger.w(TAG, "Routing failed: ${e.javaClass.simpleName}")
            return@withContext false
        }

        var handled = false
        for (decision in decisions) {
            // Calendar is strictly handled by CalendarProcessingCoordinator to avoid regression or double writes
            val candidate = when (decision) {
                is RoutingDecision.RouteAuto -> decision.candidate
                is RoutingDecision.RequireConfirmation -> decision.candidate
                is RoutingDecision.NeedsAttention -> decision.candidate
                is RoutingDecision.MemoryOnly -> decision.candidate
            }
            if (candidate == null || candidate.type != ActionCandidateType.TASK) {
                continue
            }

            when (decision) {
                is RoutingDecision.RouteAuto -> {
                    // High confidence & user enabled auto tasks
                    val execResult = actionRouter.executeDecision(decision, memoryItem)
                    if (execResult is DestinationExecutionResult.Success) {
                        handled = true
                        val formattedDue = candidate.dueAt?.let {
                            NotificationHelper.formatEventDateTime(it, false, zoneId)
                        } ?: ""
                        NotificationHelper.showTaskAddedNotification(
                            context = context,
                            notificationId = memoryItem.id.hashCode() + 100,
                            actionId = execResult.destinationReferenceId ?: UUID.randomUUID().toString(),
                            title = candidate.title,
                            formattedDue = formattedDue
                        )
                    }
                }

                is RoutingDecision.RequireConfirmation -> {
                    // Medium/low confidence or requires confirmation
                    val targetListId = settings.selectedTaskListId ?: "@default"
                    val existing = taskActionDao.getAction(memoryItem.id, ACTION_TYPE_CREATE, targetListId)
                    if (existing != null) {
                        logger.i(TAG, "Task action already recorded (${existing.status}) for memory ${memoryItem.id}")
                        continue
                    }

                    val actionId = UUID.randomUUID().toString()
                    val pendingAction = TaskActionEntity(
                        id = actionId,
                        sourceMemoryId = memoryItem.id,
                        actionType = ACTION_TYPE_CREATE,
                        taskListId = targetListId,
                        taskListTitle = settings.selectedTaskListTitle,
                        destinationTaskId = null,
                        status = "CONFIRM_PENDING",
                        title = candidate.title.take(200),
                        originalDueAt = candidate.dueAt?.toEpochMilli(),
                        googleDue = candidate.dueAt?.let { "${it.atZone(zoneId).toLocalDate()}T00:00:00.000Z" },
                        notes = candidate.notes?.take(1000),
                        attemptCount = 0
                    )

                    try {
                        taskActionDao.insert(pendingAction)
                    } catch (e: Exception) {
                        logger.w(TAG, "Failed inserting confirm pending task: ${e.javaClass.simpleName}")
                        continue
                    }

                    val formattedDue = candidate.dueAt?.let {
                        NotificationHelper.formatEventDateTime(it, false, zoneId)
                    } ?: ""
                    NotificationHelper.showTaskConfirmNotification(
                        context = context,
                        notificationId = memoryItem.id.hashCode() + 100,
                        actionId = actionId,
                        memoryId = memoryItem.id,
                        title = candidate.title,
                        formattedDue = formattedDue
                    )
                    handled = true
                }

                is RoutingDecision.NeedsAttention -> {
                    logger.w(TAG, "Task action requires attention: ${decision.reason}")
                }

                is RoutingDecision.MemoryOnly -> {
                    logger.d(TAG, "Task candidate routed to MemoryOnly: ${decision.reason}")
                }
            }
        }

        handled
    }

    suspend fun reconcileInFlightActions(): Int = withContext(Dispatchers.IO) {
        val creatingActions = try {
            taskActionDao.getInFlightCreatingActions(limit = 10)
        } catch (e: Exception) {
            logger.w(TAG, "Failed querying in-flight actions: ${e.javaClass.simpleName}")
            return@withContext 0
        }

        var count = 0
        val now = System.currentTimeMillis()
        for (action in creatingActions) {
            // Stale CREATING action (older than 30 seconds)
            if (now - action.createdAt > 30_000L) {
                // Check if Google Tasks is authorized
                if (!googleTasksDestination.isAuthorized()) {
                    taskActionDao.updateStatusAndError(action.id, "NEEDS_ATTENTION", "Reconciliation: auth missing")
                    count++
                    continue
                }

                val candidate = ActionCandidate(
                    type = ActionCandidateType.TASK,
                    title = action.title,
                    confidence = 1.0f,
                    dueAt = action.originalDueAt?.let { Instant.ofEpochMilli(it) },
                    notes = action.notes,
                    sourceMemoryId = action.sourceMemoryId
                )

                val result = googleTasksDestination.createTask(candidate)
                if (result is DestinationExecutionResult.Success) {
                    count++
                }
            }
        }
        count
    }
}
