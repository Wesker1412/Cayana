package com.cayana.source.recording

/**
 * Concentrated configuration parameters for recording chunking and execution policy.
 */
object RecordingConfig {
    /**
     * Duration of each audio processing chunk (30 seconds).
     */
    const val CHUNK_DURATION_MS: Long = 30_000L

    /**
     * Recordings longer than this threshold (5 minutes) request battery-not-low constraints in background.
     */
    const val LONG_RECORDING_BATTERY_THRESHOLD_MS: Long = 300_000L

    /**
     * Maximum transient retries per chunk before flagging FAILED_RETRYABLE.
     */
    const val MAX_CHUNK_RETRIES: Int = 3
}
