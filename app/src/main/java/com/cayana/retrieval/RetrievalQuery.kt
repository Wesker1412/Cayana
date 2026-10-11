package com.cayana.retrieval

import com.cayana.source.SourceType
import java.time.Clock
import java.time.ZoneId

/**
 * Time range constraint hint for retrieval.
 */
data class TimeHint(
    val startMillis: Long,
    val endMillis: Long,
    val label: String
)

/**
 * Configurable options for a retrieval query.
 * Injectable Clock and ZoneId allow fully deterministic testing.
 */
data class RetrievalOptions(
    val topK: Int? = null,
    val filterSourceType: SourceType? = null,
    val timeHint: TimeHint? = null,
    val clock: Clock = Clock.systemDefaultZone(),
    val zoneId: ZoneId = ZoneId.systemDefault()
)

/**
 * Natural language query submitted to the local memory retriever.
 */
data class RetrievalQuery(
    val text: String,
    val options: RetrievalOptions = RetrievalOptions()
)
