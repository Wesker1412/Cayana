package com.cayana.actions.destination

import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationType

interface DestinationRegistry {
    fun getDestination(type: DestinationType): ActionDestination?
    fun getDestinationForCandidateType(candidateType: ActionCandidateType): ActionDestination? {
        return when (candidateType) {
            ActionCandidateType.CALENDAR_EVENT -> getDestination(DestinationType.CALENDAR)
            ActionCandidateType.TASK -> getDestination(DestinationType.TASK)
            ActionCandidateType.NOTE -> getDestination(DestinationType.NOTE)
        }
    }
    fun register(destination: ActionDestination)
}

class DefaultDestinationRegistry(
    destinations: List<ActionDestination> = emptyList()
) : DestinationRegistry {
    private val map = destinations.associateBy { it.destinationType }.toMutableMap()

    override fun getDestination(type: DestinationType): ActionDestination? = map[type]

    override fun register(destination: ActionDestination) {
        map[destination.destinationType] = destination
    }
}
