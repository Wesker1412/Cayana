package com.cayana.actions.classifier

import com.cayana.actions.model.ActionCandidate
import com.cayana.memory.model.MemoryItem
import java.time.Instant
import java.time.ZoneId

interface ActionClassifier {
    fun classify(
        memoryItem: MemoryItem,
        rawText: String?,
        referenceTime: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<ActionCandidate>
}
