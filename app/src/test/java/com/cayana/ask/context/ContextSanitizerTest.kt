package com.cayana.ask.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSanitizerTest {

    @Test
    fun `redacts bearer tokens and jwt tokens`() {
        val input = "Header: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisToken12345"
        val sanitized = ContextSanitizer.sanitize(input)

        assertFalse(sanitized.contains("eyJhbGciOi"))
        assertFalse(sanitized.contains("doNotLeakThisToken12345"))
        assertTrue(sanitized.contains("Bearer [REDACTED_TOKEN]"))
    }

    @Test
    fun `redacts api keys`() {
        val input = "Use key sk-abcdef1234567890abcdef and Google key AIzaSyA1234567890abcdefghijklmnopqrstuv"
        val sanitized = ContextSanitizer.sanitize(input)

        assertFalse(sanitized.contains("sk-abcdef1234567890abcdef"))
        assertFalse(sanitized.contains("AIzaSyA1234567890abcdefghijklmnopqrstuv"))
        assertTrue(sanitized.contains("[REDACTED_API_KEY]"))
    }

    @Test
    fun `redacts passwords and credentials`() {
        val input = "Database config: password=SuperSecretPassword123! and secret: confidentialKey"
        val sanitized = ContextSanitizer.sanitize(input)

        assertFalse(sanitized.contains("SuperSecretPassword123!"))
        assertFalse(sanitized.contains("confidentialKey"))
        assertTrue(sanitized.contains("password: [REDACTED_CREDENTIAL]"))
        assertTrue(sanitized.contains("secret: [REDACTED_CREDENTIAL]"))
    }

    @Test
    fun `redacts recovery keys and backup keys`() {
        val input = "My Cayana Recovery Key: ABCD-EFGH-IJKL-MNOP and Seed phrase: apple orange banana guitar"
        val sanitized = ContextSanitizer.sanitize(input)

        assertFalse(sanitized.contains("ABCD-EFGH-IJKL-MNOP"))
        assertFalse(sanitized.contains("apple orange banana guitar"))
        assertTrue(sanitized.contains("[REDACTED_RECOVERY_KEY]"))
    }

    @Test
    fun `redacts local android and file uris`() {
        val input = "Saved image to content://media/external/images/media/42 and file:///storage/emulated/0/DCIM/pic.jpg"
        val sanitized = ContextSanitizer.sanitize(input)

        assertFalse(sanitized.contains("content://"))
        assertFalse(sanitized.contains("file:///"))
        assertTrue(sanitized.contains("[REDACTED_URI]"))
    }

    @Test
    fun `sanitizes web urls by stripping query parameters, fragments, and userinfo`() {
        val input = "Link: https://user:pass@example.com/checkout?token=secret123&track=99#payment"
        val sanitized = ContextSanitizer.sanitize(input)

        assertFalse(sanitized.contains("user:pass@"))
        assertFalse(sanitized.contains("token=secret123"))
        assertFalse(sanitized.contains("#payment"))
        assertEquals("Link: https://example.com/checkout", sanitized)
    }

    @Test
    fun `handles empty and blank strings gracefully`() {
        assertEquals("", ContextSanitizer.sanitize(null))
        assertEquals("", ContextSanitizer.sanitize(""))
        assertEquals("", ContextSanitizer.sanitize("   "))
    }
}
