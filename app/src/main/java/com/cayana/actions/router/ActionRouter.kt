package com.cayana.actions.router

import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.RoutingDecision
import com.cayana.memory.model.MemoryItem
import java.time.Instant
import java.time.ZoneId

interface ActionRouter {
    suspend fun route(
        memoryItem: MemoryItem,
        rawText: String? = memoryItem.rawText,
        referenceTime: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<RoutingDecision>

    suspend fun executeDecision(
        decision: RoutingDecision,
        memoryItem: MemoryItem
    ): DestinationExecutionResult
}
