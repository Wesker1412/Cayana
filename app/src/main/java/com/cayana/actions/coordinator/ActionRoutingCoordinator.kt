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
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.core.notification.NotificationHelper
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

open class ActionRoutingCoordinator(
    private val context: Context,
    private val actionRouter: ActionRouter? = null,
    private val taskActionDao: TaskActionDao? = null,
    private val googleTasksDestination: GoogleTasksDestination? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val memoryRepository: MemoryRepository? = null,
    private val logger: CayanaLogger = DefaultCayanaLogger()
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
    open suspend fun process(
        memoryItem: MemoryItem,
        rawText: String?,
        referenceTime: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean = withContext(Dispatchers.IO) {
        if (rawText.isNullOrBlank()) {
            return@withContext false
        }

        val settingsRepo = settingsRepository ?: return@withContext false
        val router = actionRouter ?: return@withContext false
        val dao = taskActionDao ?: return@withContext false

        // Idempotency check: if already processed or has action in any state, skip
        val existingActions = dao.getActionsForMemory(memoryItem.id)
        if (existingActions.any { it.status == "CREATED" || it.status == "CONFIRM_PENDING" || it.status == "IGNORED" || it.status == "CREATING" || it.status == "COMPLETED" }) {
            logger.i(TAG, "Task action already recorded for memory ${memoryItem.id}; skipping")
            return@withContext false
        }

        val settings = try {
            settingsRepo.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "Failed loading settings: ${e.javaClass.simpleName}")
            return@withContext false
        }

        // Bounded reconciliation of any pending CREATING actions (non-recursive)
        try {
            reconcileInFlightActions(recoverMemories = false)
        } catch (e: Exception) {
            logger.w(TAG, "Reconciling in-flight actions error: ${e.javaClass.simpleName}")
        }

        val decisions = try {
            router.route(memoryItem, rawText, referenceTime, zoneId)
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
                    if (!isMemoryEligibleForAutoTasks(memoryItem, settings.autoTasksEligibilityEpoch)) {
                        logger.i(TAG, "Memory ${memoryItem.id} is ineligible for auto tasks (capturedAt=${memoryItem.capturedAt}, epoch=${settings.autoTasksEligibilityEpoch}); auto-route skipped")
                        continue
                    }
                    val targetListId = settings.selectedTaskListId ?: "@default"
                    val existing = dao.getAction(memoryItem.id, ACTION_TYPE_CREATE, targetListId)
                    if (existing != null && (existing.status == "CONFIRM_PENDING" || existing.status == "IGNORED" || existing.status == "CREATED")) {
                        logger.i(TAG, "Task action already in ${existing.status} for memory ${memoryItem.id}; auto-route skipped")
                        continue
                    }

                    // High confidence & user enabled auto tasks
                    val execResult = router.executeDecision(decision, memoryItem)
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
                    val existing = dao.getAction(memoryItem.id, ACTION_TYPE_CREATE, targetListId)
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
                        dao.insert(pendingAction)
                    } catch (e: Exception) {
                        logger.w(TAG, "Failed inserting confirm pending task: ${e.javaClass.simpleName}")
                        continue
                    }

                    val formattedDue = candidate.dueAt?.let {
                        NotificationHelper.formatEventDateTime(it, false, zoneId)
                    } ?: ""
                    val posted = NotificationHelper.showTaskConfirmNotification(
                        context = context,
                        notificationId = memoryItem.id.hashCode() + 100,
                        actionId = actionId,
                        memoryId = memoryItem.id,
                        title = candidate.title,
                        formattedDue = formattedDue
                    )
                    if (posted) {
                        handled = true
                    } else {
                        dao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "通知未開啟或發送失敗，無法提示使用者確認")
                        logger.w(TAG, "Notification denied or failed to post for action $actionId; downgraded to NEEDS_ATTENTION")
                        handled = false
                    }
                }

                is RoutingDecision.NeedsAttention -> {
                    logger.w(TAG, "Task action requires attention: ${decision.reason}")
                }

                is RoutingDecision.MemoryOnly -> {
                    logger.d(TAG, "Task candidate routed to MemoryOnly: ${decision.reason}")
                    if (dao.getActionsForMemory(memoryItem.id).isEmpty()) {
                        val marker = TaskActionEntity(
                            id = UUID.randomUUID().toString(),
                            sourceMemoryId = memoryItem.id,
                            actionType = "ROUTED",
                            taskListId = "@none",
                            taskListTitle = null,
                            destinationTaskId = null,
                            status = "COMPLETED",
                            title = memoryItem.title ?: "MemoryOnly",
                            attemptCount = 0
                        )
                        dao.insert(marker)
                    }
                }
            }
        }

        if (!handled && dao.getActionsForMemory(memoryItem.id).isEmpty()) {
            val marker = TaskActionEntity(
                id = UUID.randomUUID().toString(),
                sourceMemoryId = memoryItem.id,
                actionType = "ROUTED",
                taskListId = "@none",
                taskListTitle = null,
                destinationTaskId = null,
                status = "COMPLETED",
                title = memoryItem.title ?: "MemoryOnly",
                attemptCount = 0
            )
            dao.insert(marker)
        }

        handled
    }

    suspend fun reconcileInFlightActions(recoverMemories: Boolean = true): Int = withContext(Dispatchers.IO) {
        val dao = taskActionDao ?: return@withContext 0
        val destination = googleTasksDestination ?: return@withContext 0

        val creatingActions = try {
            dao.getInFlightCreatingActions(limit = 10)
        } catch (e: Exception) {
            logger.w(TAG, "Failed querying in-flight actions: ${e.javaClass.simpleName}")
            emptyList()
        }

        var count = 0
        val now = System.currentTimeMillis()
        for (action in creatingActions) {
            // Stale CREATING action (older than 30 seconds since last state update)
            if (now - action.updatedAt > 30_000L) {
                val result = destination.reconcileAction(action)
                if (result is DestinationExecutionResult.Success) {
                    count++
                }
            }
        }

        if (recoverMemories) {
            count += recoverUnroutedMemories()
        }

        count
    }

    suspend fun recoverUnroutedMemories(memoryRepo: MemoryRepository? = null): Int = withContext(Dispatchers.IO) {
        val repo = memoryRepo ?: memoryRepository ?: return@withContext 0
        val dao = taskActionDao ?: return@withContext 0
        val settingsRepo = settingsRepository ?: return@withContext 0

        val settings = try {
            settingsRepo.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "Failed reading settings for recovery: ${e.javaClass.simpleName}")
            return@withContext 0
        }

        // Recovery Eligibility Boundary:
        // Must be connected, auto-tasks enabled, and have a valid auto-tasks eligibility epoch.
        if (!settings.isGoogleTasksConnected ||
            settings.googleTasksAuthStatus != com.cayana.actions.tasks.auth.TasksAuthStatus.CONNECTED ||
            !settings.autoTasksEnabled ||
            settings.autoTasksEligibilityEpoch <= 0L) {
            logger.d(TAG, "recoverUnroutedMemories: Tasks not connected/auto-enabled or no eligibility epoch; skipping recovery")
            return@withContext 0
        }

        val eligibleMemories = try {
            repo.getMemoriesCapturedSince(
                sourceType = SourceType.SCREENSHOT,
                sinceCapturedAt = settings.autoTasksEligibilityEpoch,
                limit = 50
            )
        } catch (e: Exception) {
            logger.w(TAG, "Failed reading memories for recovery: ${e.javaClass.simpleName}")
            return@withContext 0
        }

        var recoveredCount = 0

        for (memory in eligibleMemories) {
            // Apply the unified eligibility rule:
            // Screenshots captured or created before the eligibility epoch are strictly ineligible.
            if (!isMemoryEligibleForAutoTasks(memory, settings.autoTasksEligibilityEpoch)) {
                continue
            }

            if (!memory.rawText.isNullOrBlank()) {
                val existing = dao.getActionsForMemory(memory.id)
                if (existing.isEmpty()) {
                    logger.i(TAG, "Recovering eligible unrouted memory ${memory.id}")
                    val handled = process(memory, memory.rawText)
                    if (handled) {
                        recoveredCount++
                    }
                }
            }
        }
        recoveredCount
    }

    fun isMemoryEligibleForAutoTasks(memory: MemoryItem, eligibilityEpoch: Long): Boolean {
        if (eligibilityEpoch <= 0L) return false
        if (memory.capturedAt < eligibilityEpoch) return false
        if (memory.createdAt > 0L && memory.createdAt < eligibilityEpoch) return false
        return true
    }
}
