package com.cayana.actions.router

import com.cayana.actions.classifier.ActionClassifier
import com.cayana.actions.destination.DestinationRegistry
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.RoutingDecision
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import java.time.Instant
import java.time.ZoneId

class DefaultActionRouter(
    private val classifier: ActionClassifier,
    private val destinationRegistry: DestinationRegistry,
    private val routingHistoryTracker: ActionRoutingHistoryTracker = DefaultActionRoutingHistoryTracker(),
    private val memoryRepository: MemoryRepository? = null,
    private val logger: CayanaLogger
) : ActionRouter {

    companion object {
        private const val TAG = "ActionRouter"
    }

    override suspend fun route(
        memoryItem: MemoryItem,
        rawText: String?,
        referenceTime: Instant,
        zoneId: ZoneId
    ): List<RoutingDecision> {
        val candidates = classifier.classify(memoryItem, rawText, referenceTime, zoneId)
        if (candidates.isEmpty()) {
            return listOf(RoutingDecision.MemoryOnly(reason = "No actionable candidates detected"))
        }

        return candidates.map { candidate ->
            evaluateCandidate(candidate, memoryItem)
        }
    }

    private suspend fun evaluateCandidate(
        candidate: ActionCandidate,
        memoryItem: MemoryItem
    ): RoutingDecision {
        val destination = destinationRegistry.getDestinationForCandidateType(candidate.type)

        // 1. 未配置 Destination → Memory only
        if (destination == null || !destination.isConfigured()) {
            logger.d(TAG, "Destination for ${candidate.type} is not configured; Memory only for memory ${memoryItem.id}")
            return RoutingDecision.MemoryOnly(
                reason = "Destination for ${candidate.type} is not configured",
                candidate = candidate
            )
        }

        // 2. 授權失效 → Memory only + needs attention
        if (!destination.isAuthorized()) {
            logger.w(TAG, "Destination for ${candidate.type} authorization missing or expired for memory ${memoryItem.id}")
            return RoutingDecision.NeedsAttention(
                candidate = candidate,
                destinationType = destination.destinationType,
                reason = "Destination authorization missing or expired"
            )
        }

        // 3. 分類信心不足 → 不自動執行 (Memory only)
        if (candidate.confidence < ActionRoutingPolicy.MIN_CONFIDENCE_THRESHOLD) {
            logger.d(TAG, "Candidate confidence ${candidate.confidence} below threshold for ${candidate.type}; Memory only")
            return RoutingDecision.MemoryOnly(
                reason = "Candidate confidence too low: ${candidate.confidence}",
                candidate = candidate
            )
        }

        // 4. 重複檢查 (Duplicate routing check / idempotency)
        if (routingHistoryTracker.hasBeenRouted(memoryItem.id, candidate.type)) {
            logger.i(TAG, "Candidate ${candidate.type} has already been routed for memory ${memoryItem.id}; skipping to prevent duplication")
            return RoutingDecision.MemoryOnly(
                reason = "Duplicate routing: action already exists for memory ${memoryItem.id}",
                candidate = candidate
            )
        }

        // 5. 高誤判風險 / 中等信心 → 明確確認 (RequireConfirmation)
        if (candidate.requiresConfirmation || candidate.confidence < ActionRoutingPolicy.AUTO_EXECUTE_CONFIDENCE_THRESHOLD) {
            val reason = if (candidate.requiresConfirmation) {
                "High false positive risk"
            } else {
                "Confidence below automatic threshold (${candidate.confidence})"
            }
            logger.i(TAG, "Routing ${candidate.type} requires confirmation: $reason")
            return RoutingDecision.RequireConfirmation(
                candidate = candidate,
                destinationType = destination.destinationType,
                reason = reason
            )
        }

        // 6. 通過所有檢查 → 自動執行
        logger.i(TAG, "Routing ${candidate.type} automatically for memory ${memoryItem.id}")
        return RoutingDecision.RouteAuto(
            candidate = candidate,
            destinationType = destination.destinationType
        )
    }

    override suspend fun executeDecision(
        decision: RoutingDecision,
        memoryItem: MemoryItem
    ): DestinationExecutionResult {
        return when (decision) {
            is RoutingDecision.RouteAuto -> {
                val destination = destinationRegistry.getDestination(decision.destinationType)
                if (destination == null) {
                    return DestinationExecutionResult.Failure(
                        reason = "Destination ${decision.destinationType} not found",
                        canRetry = false
                    )
                }

                // Double check duplicate protection before executing
                if (routingHistoryTracker.hasBeenRouted(memoryItem.id, decision.candidate.type)) {
                    return DestinationExecutionResult.Skipped
                }

                try {
                    // Send strictly bounded ActionCandidate, never full raw private memory or credentials
                    val result = destination.execute(decision.candidate, memoryItem)
                    if (result is DestinationExecutionResult.Success) {
                        routingHistoryTracker.recordRouted(
                            memoryItem.id,
                            decision.candidate.type,
                            result.destinationReferenceId
                        )
                    }
                    // Failure rule: Destination 執行失敗 → Memory 不回滾，永不刪除或修改 MemoryItem
                    result
                } catch (e: Exception) {
                    logger.e(TAG, "Destination execution exception for memory ${memoryItem.id}: ${e.message}")
                    // Crucial: Memory in memoryRepository is strictly preserved and never touched or rolled back!
                    DestinationExecutionResult.Failure(
                        reason = "Destination execution failed: ${e.message}",
                        canRetry = true
                    )
                }
            }
            is RoutingDecision.RequireConfirmation -> {
                logger.i(TAG, "Action requires explicit user confirmation; skipped automatic execution")
                DestinationExecutionResult.Skipped
            }
            is RoutingDecision.NeedsAttention -> {
                logger.w(TAG, "Action requires authorization attention; skipped automatic execution")
                DestinationExecutionResult.NeedsAttention(decision.reason)
            }
            is RoutingDecision.MemoryOnly -> {
                DestinationExecutionResult.Skipped
            }
        }
    }
}
