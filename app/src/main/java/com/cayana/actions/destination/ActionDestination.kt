package com.cayana.actions.destination

import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.DestinationType
import com.cayana.memory.model.MemoryItem

interface ActionDestination {
    val destinationType: DestinationType
    suspend fun isConfigured(): Boolean
    suspend fun isAuthorized(): Boolean

    suspend fun execute(
        candidate: ActionCandidate,
        memoryItem: MemoryItem
    ): DestinationExecutionResult
}
