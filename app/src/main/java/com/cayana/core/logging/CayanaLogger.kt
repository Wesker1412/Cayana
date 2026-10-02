package com.cayana.core.logging

import android.util.Log

interface CayanaLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Specifically logs memory-related operational events safely without logging
     * actual private memory contents.
     */
    fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?)
}

class DefaultCayanaLogger(
    private val isDebugEnabled: Boolean = true
) : CayanaLogger {

    override fun d(tag: String, message: String) {
        if (isDebugEnabled) {
            Log.d(tag, message)
        }
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    override fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?) {
        val sanitized = PrivacySanitizer.sanitizeMemoryText(rawContent)
        i(tag, "MemoryEvent: event=$eventName, id=$memoryId, content=$sanitized")
    }
}
