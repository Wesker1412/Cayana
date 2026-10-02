package com.cayana.core.logging

import android.util.Log

interface CayanaLogger {
    /**
     * General debug log.
     * CRITICAL PRIVACY RULE: Never pass raw OCR text, STT transcripts, or user memory content here.
     * For memory operational events, use [logMemoryEvent].
     */
    fun d(tag: String, message: String)

    /**
     * General info log.
     * CRITICAL PRIVACY RULE: Never pass raw OCR text, STT transcripts, or user memory content here.
     */
    fun i(tag: String, message: String)

    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Specifically logs memory-related operational events safely without logging
     * actual private memory contents.
     */
    fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?)

    companion object : CayanaLogger {
        @Volatile
        private var delegate: CayanaLogger = DefaultCayanaLogger()

        fun setDelegate(logger: CayanaLogger) {
            delegate = logger
        }

        override fun d(tag: String, message: String) = delegate.d(tag, message)
        override fun i(tag: String, message: String) = delegate.i(tag, message)
        override fun w(tag: String, message: String, throwable: Throwable?) = delegate.w(tag, message, throwable)
        override fun e(tag: String, message: String, throwable: Throwable?) = delegate.e(tag, message, throwable)
        override fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?) =
            delegate.logMemoryEvent(tag, eventName, memoryId, rawContent)
    }
}

class DefaultCayanaLogger(
    private val isDebugEnabled: Boolean = true
) : CayanaLogger {

    override fun d(tag: String, message: String) {
        if (isDebugEnabled) {
            Log.d(tag, PrivacySanitizer.guardAgainstMemoryLeakage(message))
        }
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, PrivacySanitizer.guardAgainstMemoryLeakage(message))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(message)
        if (throwable != null) {
            Log.w(tag, guarded, throwable)
        } else {
            Log.w(tag, guarded)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(message)
        if (throwable != null) {
            Log.e(tag, guarded, throwable)
        } else {
            Log.e(tag, guarded)
        }
    }

    override fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?) {
        val sanitized = PrivacySanitizer.sanitizeMemoryText(rawContent)
        i(tag, "MemoryEvent: event=$eventName, id=$memoryId, content=$sanitized")
    }
}
