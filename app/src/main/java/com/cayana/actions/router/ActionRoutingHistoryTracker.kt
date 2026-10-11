package com.cayana.actions.router

import com.cayana.actions.model.ActionCandidateType
import com.cayana.calendar.data.CalendarActionDao
import java.util.concurrent.ConcurrentHashMap

interface ActionRoutingHistoryTracker {
    suspend fun hasBeenRouted(sourceMemoryId: String, type: ActionCandidateType): Boolean
    suspend fun recordRouted(sourceMemoryId: String, type: ActionCandidateType, actionRefId: String? = null)
}

class DefaultActionRoutingHistoryTracker(
    private val calendarActionDao: CalendarActionDao? = null
) : ActionRoutingHistoryTracker {
    private val memoryRoutedActions = ConcurrentHashMap<String, MutableSet<ActionCandidateType>>()

    override suspend fun hasBeenRouted(sourceMemoryId: String, type: ActionCandidateType): Boolean {
        if (type == ActionCandidateType.CALENDAR_EVENT && calendarActionDao != null) {
            val calendarActions = try {
                calendarActionDao.getActionsForMemory(sourceMemoryId)
            } catch (_: Exception) {
                emptyList()
            }
            if (calendarActions.isNotEmpty()) {
                return true
            }
        }
        val set = memoryRoutedActions[sourceMemoryId] ?: return false
        return set.contains(type)
    }

    override suspend fun recordRouted(
        sourceMemoryId: String,
        type: ActionCandidateType,
        actionRefId: String?
    ) {
        val set = memoryRoutedActions.computeIfAbsent(sourceMemoryId) { ConcurrentHashMap.newKeySet() }
        set.add(type)
    }
}
