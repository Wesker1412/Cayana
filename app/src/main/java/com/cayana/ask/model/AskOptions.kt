package com.cayana.ask.model

import java.time.Clock
import java.time.ZoneId

/**
 * Configuration options for an Ask query.
 */
data class AskOptions(
    val maxContextBytes: Int = DEFAULT_MAX_CONTEXT_BYTES,
    val topK: Int = DEFAULT_TOP_K,
    val bypassAuthorizationGate: Boolean = false,
    val clock: Clock = Clock.systemDefaultZone(),
    val zoneId: ZoneId = ZoneId.systemDefault()
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT_BYTES = 16 * 1024 // 16 KB hard wire budget
        const val DEFAULT_TOP_K = 10
    }
}
