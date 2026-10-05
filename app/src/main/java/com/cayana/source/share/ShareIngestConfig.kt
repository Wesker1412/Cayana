package com.cayana.source.share

object ShareIngestConfig {
    /**
     * Maximum allowed stream bytes for a single shared file (50 MB).
     * Prevents exported Share target from filling device storage.
     */
    const val MAX_TEMP_BYTES: Long = 50 * 1024 * 1024L

    /**
     * Maximum number of items processed from ACTION_SEND_MULTIPLE.
     */
    const val MAX_ITEM_COUNT: Int = 10

    /**
     * Supported MIME types for ShareReceiver.
     */
    val SUPPORTED_MIME_PATTERNS = listOf(
        "text/plain",
        "text/*",
        "image/*",
        "application/pdf"
    )

    /**
     * Dedup window in milliseconds (60 seconds) to prevent duplicate ingest
     * caused by Activity recreate, onNewIntent, or double dispatch.
     */
    const val DEDUP_WINDOW_MS: Long = 60_000L
}
