package com.cayana.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySanitizerTest {

    @Test
    fun `sanitizeMemoryText redacts raw sensitive text and returns length metadata only`() {
        val sensitiveOcr = "Bank statement account 1234-5678-9012 balance $50,000"
        val sanitized = PrivacySanitizer.sanitizeMemoryText(sensitiveOcr)

        assertFalse("Log must never contain account number", sanitized.contains("1234"))
        assertFalse("Log must never contain monetary balance", sanitized.contains("50,000"))
        assertEquals("[REDACTED_MEMORY_LEN_${sensitiveOcr.length}]", sanitized)
    }

    @Test
    fun `sanitizeMemoryText handles empty and null strings safely`() {
        assertEquals("[EMPTY]", PrivacySanitizer.sanitizeMemoryText(null))
        assertEquals("[EMPTY]", PrivacySanitizer.sanitizeMemoryText(""))
    }

    @Test
    fun `sanitizeUri strips private local directory paths`() {
        val privatePath = "/storage/emulated/0/DCIM/Screenshots/Screenshot_20261002_080000.png"
        val sanitized = PrivacySanitizer.sanitizeUri(privatePath)

        assertEquals(".../Screenshot_20261002_080000.png", sanitized)
        assertFalse(sanitized.contains("storage/emulated"))
    }

    @Test
    fun `guardAgainstMemoryLeakage redacts accidental memory text in log messages`() {
        val message = "Processing item MemoryItem(id=1, rawText=Confidential user note, title=Note)"
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(message)

        assertFalse("Raw private memory text must be redacted", guarded.contains("Confidential user note"))
        assertTrue("Redacted marker must be present", guarded.contains("[REDACTED_BY_PRIVACY_GUARD]"))
    }
}
