package com.cayana.processing

enum class ProcessingState {
    PENDING,
    PROCESSING,
    WAITING_FOR_MODEL,
    COMPLETED,
    COMPLETED_WITHOUT_TEXT,
    FAILED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT
}
