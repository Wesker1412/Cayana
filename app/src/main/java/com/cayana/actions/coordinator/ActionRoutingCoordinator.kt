package com.cayana.actions.coordinator

import android.content.Context
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.RoutingDecision
import com.cayana.actions.notion.data.NotionActionDao
import com.cayana.actions.notion.data.NotionActionEntity
import com.cayana.actions.notion.destination.NotionDestination
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
import com.cayana.ui.settings.repository.NotionTransmissionPolicy
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
    private val notionActionDao: NotionActionDao? = null,
    private val notionDestination: NotionDestination? = null,
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
     * Returns true if an action was executed or confirmation notification was posted.
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
        val taskDao = taskActionDao
        val notionDao = notionActionDao

        // Idempotency check: if all candidate types are already handled, skip
        val existingTaskActions = taskDao?.getActionsForMemory(memoryItem.id) ?: emptyList()
        val taskAlreadyHandled = existingTaskActions.any {
            it.status in listOf("CREATED", "CONFIRM_PENDING", "IGNORED", "CREATING", "COMPLETED")
        }

        val existingNotionActions = notionDao?.getActionsForMemory(memoryItem.id) ?: emptyList()
        val notionAlreadyHandled = existingNotionActions.any {
            it.status in listOf("CREATED", "CONFIRM_PENDING", "IGNORED", "CREATING", "COMPLETED")
        }

        if (taskAlreadyHandled && notionAlreadyHandled) {
            logger.i(TAG, "All actions already recorded for memory ${memoryItem.id}; skipping")
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
            val candidate = when (decision) {
                is RoutingDecision.RouteAuto -> decision.candidate
                is RoutingDecision.RequireConfirmation -> decision.candidate
                is RoutingDecision.NeedsAttention -> decision.candidate
                is RoutingDecision.MemoryOnly -> decision.candidate
            } ?: continue

            // 1. Google Tasks routing
            if (candidate.type == ActionCandidateType.TASK && taskDao != null && !taskAlreadyHandled) {
                when (decision) {
                    is RoutingDecision.RouteAuto -> {
                        if (!isMemoryEligibleForAutoTasks(memoryItem, settings.autoTasksEligibilityEpoch)) {
                            logger.i(TAG, "Memory ${memoryItem.id} is ineligible for auto tasks (capturedAt=${memoryItem.capturedAt}, epoch=${settings.autoTasksEligibilityEpoch}); auto-route skipped")
                            continue
                        }
                        val targetListId = settings.selectedTaskListId ?: "@default"
                        val existing = taskDao.getAction(memoryItem.id, ACTION_TYPE_CREATE, targetListId)
                        if (existing != null && (existing.status == "CONFIRM_PENDING" || existing.status == "IGNORED" || existing.status == "CREATED")) {
                            logger.i(TAG, "Task action already in ${existing.status} for memory ${memoryItem.id}; auto-route skipped")
                            continue
                        }

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
                        val targetListId = settings.selectedTaskListId ?: "@default"
                        val existing = taskDao.getAction(memoryItem.id, ACTION_TYPE_CREATE, targetListId)
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
                            taskDao.insert(pendingAction)
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
                            taskDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "通知未開啟或發送失敗，無法提示使用者確認")
                            logger.w(TAG, "Notification denied or failed to post for action $actionId; downgraded to NEEDS_ATTENTION")
                        }
                    }

                    is RoutingDecision.NeedsAttention -> {
                        logger.w(TAG, "Task action requires attention: ${decision.reason}")
                    }

                    is RoutingDecision.MemoryOnly -> {
                        logger.d(TAG, "Task candidate routed to MemoryOnly: ${decision.reason}")
                        if (taskDao.getActionsForMemory(memoryItem.id).isEmpty()) {
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
                            taskDao.insert(marker)
                        }
                    }
                }
            }

            // 2. Notion Notes routing
            if (candidate.type == ActionCandidateType.NOTE && notionDao != null && !notionAlreadyHandled) {
                val targetId = settings.selectedNotionTargetId
                if (settings.notionTransmissionPolicy == NotionTransmissionPolicy.OFF || targetId.isNullOrBlank()) {
                    logger.d(TAG, "Notion policy OFF or no target selected; skipping Note routing")
                    continue
                }

                when (settings.notionTransmissionPolicy) {
                    NotionTransmissionPolicy.ASK_EACH_TIME -> {
                        // Always requires user confirmation
                        val existing = notionDao.getAction(memoryItem.id, NotionDestination.ACTION_TYPE_CREATE, targetId)
                        if (existing != null) {
                            logger.i(TAG, "Notion action already recorded (${existing.status}) for memory ${memoryItem.id}")
                            continue
                        }

                        val actionId = UUID.randomUUID().toString()
                        val pendingAction = NotionActionEntity(
                            id = actionId,
                            sourceMemoryId = memoryItem.id,
                            actionType = NotionDestination.ACTION_TYPE_CREATE,
                            targetId = targetId,
                            targetTitle = settings.selectedNotionTargetTitle,
                            targetType = settings.selectedNotionTargetType?.uppercase() ?: "PAGE",
                            destinationPageId = null,
                            destinationPageUrl = null,
                            status = "CONFIRM_PENDING",
                            title = candidate.title.take(200),
                            content = candidate.notes?.take(2000),
                            attemptCount = 0
                        )

                        try {
                            notionDao.insert(pendingAction)
                        } catch (e: Exception) {
                            logger.w(TAG, "Failed inserting confirm pending notion action: ${e.javaClass.simpleName}")
                            continue
                        }

                        val posted = NotificationHelper.showNotionConfirmNotification(
                            context = context,
                            notificationId = memoryItem.id.hashCode() + 200,
                            actionId = actionId,
                            memoryId = memoryItem.id,
                            title = candidate.title,
                            targetTitle = settings.selectedNotionTargetTitle,
                            snippet = candidate.notes?.take(150)
                        )
                        if (posted) {
                            handled = true
                        } else {
                            notionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "通知未開啟或發送失敗，無法提示使用者確認")
                            logger.w(TAG, "Notification denied or failed to post for notion action $actionId; downgraded to NEEDS_ATTENTION")
                        }
                    }

                    NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES -> {
                        val isSourcePermitted = settings.autoNotionSources.contains(memoryItem.sourceType)
                        val isEpochEligible = isMemoryEligibleForAutoNotion(memoryItem, settings.autoNotionEligibilityEpoch)

                        if (isSourcePermitted && isEpochEligible && decision is RoutingDecision.RouteAuto) {
                            val existing = notionDao.getAction(memoryItem.id, NotionDestination.ACTION_TYPE_CREATE, targetId)
                            if (existing != null && (existing.status == "CONFIRM_PENDING" || existing.status == "IGNORED" || existing.status == "CREATED")) {
                                logger.i(TAG, "Notion action already in ${existing.status} for memory ${memoryItem.id}; auto-route skipped")
                                continue
                            }

                            val execResult = router.executeDecision(decision, memoryItem)
                            if (execResult is DestinationExecutionResult.Success) {
                                handled = true
                                NotificationHelper.showNotionAddedNotification(
                                    context = context,
                                    notificationId = memoryItem.id.hashCode() + 200,
                                    actionId = execResult.destinationReferenceId ?: UUID.randomUUID().toString(),
                                    title = candidate.title,
                                    targetTitle = settings.selectedNotionTargetTitle
                                )
                            }
                        } else if (decision is RoutingDecision.RequireConfirmation || !isSourcePermitted || !isEpochEligible) {
                            val existing = notionDao.getAction(memoryItem.id, NotionDestination.ACTION_TYPE_CREATE, targetId)
                            if (existing != null) {
                                logger.i(TAG, "Notion action already recorded (${existing.status}) for memory ${memoryItem.id}")
                                continue
                            }

                            val actionId = UUID.randomUUID().toString()
                            val pendingAction = NotionActionEntity(
                                id = actionId,
                                sourceMemoryId = memoryItem.id,
                                actionType = NotionDestination.ACTION_TYPE_CREATE,
                                targetId = targetId,
                                targetTitle = settings.selectedNotionTargetTitle,
                                targetType = settings.selectedNotionTargetType?.uppercase() ?: "PAGE",
                                destinationPageId = null,
                                destinationPageUrl = null,
                                status = "CONFIRM_PENDING",
                                title = candidate.title.take(200),
                                content = candidate.notes?.take(2000),
                                attemptCount = 0
                            )

                            try {
                                notionDao.insert(pendingAction)
                            } catch (e: Exception) {
                                logger.w(TAG, "Failed inserting confirm pending notion action: ${e.javaClass.simpleName}")
                                continue
                            }

                            val posted = NotificationHelper.showNotionConfirmNotification(
                                context = context,
                                notificationId = memoryItem.id.hashCode() + 200,
                                actionId = actionId,
                                memoryId = memoryItem.id,
                                title = candidate.title,
                                targetTitle = settings.selectedNotionTargetTitle,
                                snippet = candidate.notes?.take(150)
                            )
                            if (posted) {
                                handled = true
                            } else {
                                notionDao.updateStatusAndError(actionId, "NEEDS_ATTENTION", "通知未開啟或發送失敗，無法提示使用者確認")
                                logger.w(TAG, "Notification denied or failed to post for notion action $actionId; downgraded to NEEDS_ATTENTION")
                            }
                        }
                    }

                    NotionTransmissionPolicy.OFF -> Unit
                }
            }
        }

        if (!handled && taskDao != null && taskDao.getActionsForMemory(memoryItem.id).isEmpty()) {
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
            taskDao.insert(marker)
        }

        handled
    }

    suspend fun reconcileInFlightActions(recoverMemories: Boolean = true): Int = withContext(Dispatchers.IO) {
        var count = 0
        val now = System.currentTimeMillis()

        // 1. Google Tasks reconciliation
        val taskDao = taskActionDao
        val taskDest = googleTasksDestination
        if (taskDao != null && taskDest != null) {
            val creatingTaskActions = try {
                taskDao.getInFlightCreatingActions(limit = 10)
            } catch (e: Exception) {
                logger.w(TAG, "Failed querying in-flight task actions: ${e.javaClass.simpleName}")
                emptyList()
            }
            for (action in creatingTaskActions) {
                if (now - action.updatedAt > 30_000L) {
                    val result = taskDest.reconcileAction(action)
                    if (result is DestinationExecutionResult.Success) {
                        count++
                    }
                }
            }
        }

        // 2. Notion Notes reconciliation
        val notionDao = notionActionDao
        val notionDest = notionDestination
        if (notionDao != null && notionDest != null) {
            val creatingNotionActions = try {
                notionDao.getInFlightCreatingActions(limit = 10)
            } catch (e: Exception) {
                logger.w(TAG, "Failed querying in-flight notion actions: ${e.javaClass.simpleName}")
                emptyList()
            }
            for (action in creatingNotionActions) {
                if (now - action.updatedAt > 30_000L) {
                    val result = notionDest.reconcileAction(action)
                    if (result is DestinationExecutionResult.Success) {
                        count++
                    }
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
        val settingsRepo = settingsRepository ?: return@withContext 0

        val settings = try {
            settingsRepo.getSettings().first()
        } catch (e: Exception) {
            logger.w(TAG, "Failed reading settings for recovery: ${e.javaClass.simpleName}")
            return@withContext 0
        }

        var recoveredCount = 0

        // 1. Tasks recovery
        val taskDao = taskActionDao
        if (taskDao != null &&
            settings.isGoogleTasksConnected &&
            settings.googleTasksAuthStatus == com.cayana.actions.tasks.auth.TasksAuthStatus.CONNECTED &&
            settings.autoTasksEnabled &&
            settings.autoTasksEligibilityEpoch > 0L
        ) {
            val eligibleMemories = try {
                repo.getMemoriesCapturedSince(
                    sourceType = SourceType.SCREENSHOT,
                    sinceCapturedAt = settings.autoTasksEligibilityEpoch,
                    limit = 50
                )
            } catch (e: Exception) {
                logger.w(TAG, "Failed reading memories for task recovery: ${e.javaClass.simpleName}")
                emptyList()
            }

            for (memory in eligibleMemories) {
                if (!isMemoryEligibleForAutoTasks(memory, settings.autoTasksEligibilityEpoch)) {
                    continue
                }
                if (!memory.rawText.isNullOrBlank()) {
                    val existing = taskDao.getActionsForMemory(memory.id)
                    if (existing.isEmpty()) {
                        logger.i(TAG, "Recovering eligible unrouted task memory ${memory.id}")
                        val handled = process(memory, memory.rawText)
                        if (handled) {
                            recoveredCount++
                        }
                    }
                }
            }
        }

        // 2. Notion recovery
        val notionDao = notionActionDao
        if (notionDao != null &&
            settings.isNotionConnected &&
            settings.notionAuthStatus == com.cayana.actions.notion.auth.NotionAuthStatus.CONNECTED &&
            settings.notionTransmissionPolicy == NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES &&
            settings.autoNotionEligibilityEpoch > 0L
        ) {
            for (sourceType in settings.autoNotionSources) {
                val eligibleMemories = try {
                    repo.getMemoriesCapturedSince(
                        sourceType = sourceType,
                        sinceCapturedAt = settings.autoNotionEligibilityEpoch,
                        limit = 50
                    )
                } catch (e: Exception) {
                    logger.w(TAG, "Failed reading memories for notion recovery: ${e.javaClass.simpleName}")
                    emptyList()
                }

                val targetId = settings.selectedNotionTargetId ?: continue
                for (memory in eligibleMemories) {
                    if (!isMemoryEligibleForAutoNotion(memory, settings.autoNotionEligibilityEpoch)) {
                        continue
                    }
                    if (!memory.rawText.isNullOrBlank()) {
                        val existing = notionDao.getAction(memory.id, NotionDestination.ACTION_TYPE_CREATE, targetId)
                        if (existing == null) {
                            logger.i(TAG, "Recovering eligible unrouted notion memory ${memory.id}")
                            val handled = process(memory, memory.rawText)
                            if (handled) {
                                recoveredCount++
                            }
                        }
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

    fun isMemoryEligibleForAutoNotion(memory: MemoryItem, eligibilityEpoch: Long): Boolean {
        if (eligibilityEpoch <= 0L) return false
        if (memory.capturedAt < eligibilityEpoch) return false
        if (memory.createdAt > 0L && memory.createdAt < eligibilityEpoch) return false
        return true
    }
}
