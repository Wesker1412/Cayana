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
     * Recordings up to 5 minutes (300,000 ms) are processed under normal background policy.
     */
    const val SHORT_RECORDING_THRESHOLD_MS: Long = 300_000L

    /**
     * Recordings up to 30 minutes (1,800,000 ms) require battery-not-low constraints.
     */
    const val VERY_LONG_RECORDING_THRESHOLD_MS: Long = 1_800_000L

    /**
     * Maximum transient retries per chunk before flagging FAILED_RETRYABLE.
     */
    const val MAX_CHUNK_RETRIES: Int = 3

    enum class BatteryPolicy {
        NORMAL,
        BATTERY_NOT_LOW,
        CHARGING
    }

    /**
     * Returns the appropriate BatteryPolicy based on recording duration.
     * - < 5 minutes: NORMAL background
     * - 5 to 30 minutes: BATTERY_NOT_LOW
     * - > 30 minutes: CHARGING
     */
    fun getBatteryPolicy(durationMs: Long): BatteryPolicy {
        return when {
            durationMs <= SHORT_RECORDING_THRESHOLD_MS -> BatteryPolicy.NORMAL
            durationMs <= VERY_LONG_RECORDING_THRESHOLD_MS -> BatteryPolicy.BATTERY_NOT_LOW
            else -> BatteryPolicy.CHARGING
        }
    }

    /**
     * Builds WorkManager Constraints derived strictly from the BatteryPolicy.
     */
    fun getConstraints(durationMs: Long): androidx.work.Constraints {
        val policy = getBatteryPolicy(durationMs)
        val builder = androidx.work.Constraints.Builder()
        when (policy) {
            BatteryPolicy.NORMAL -> {
                // Short recordings (< 5m): no special battery constraints
            }
            BatteryPolicy.BATTERY_NOT_LOW -> {
                builder.setRequiresBatteryNotLow(true)
            }
            BatteryPolicy.CHARGING -> {
                builder.setRequiresCharging(true)
            }
        }
        return builder.build()
    }
}
