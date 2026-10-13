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

    @Test
    fun `guardAgainstMemoryLeakage redacts credentials tokens and recovery keys in log messages`() {
        val credentialLog = "OAuth state recoveryKey=REC-1234-5678-ABCD accessToken=ya29.secretToken refreshToken=refresh.secret123 codeVerifier=verifierSecretXYZ secret=appSecret999"
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(credentialLog)

        assertFalse("Recovery key must be redacted", guarded.contains("REC-1234-5678-ABCD"))
        assertFalse("Access token must be redacted", guarded.contains("ya29.secretToken"))
        assertFalse("Refresh token must be redacted", guarded.contains("refresh.secret123"))
        assertFalse("Code verifier must be redacted", guarded.contains("verifierSecretXYZ"))
        assertFalse("Secret must be redacted", guarded.contains("appSecret999"))
        assertTrue("Guarded string must contain redacted markers", guarded.contains("[REDACTED_BY_PRIVACY_GUARD]"))
    }

    @Test
    fun `guardAgainstMemoryLeakage redacts multiline and spaced rawText without leaving partial text`() {
        val multilineMessage = "Event MemoryItem(id=99, rawText=Confidential user note with spaces, punctuation! And a second line\nwith more private data., title=SafeTitle, status=ACTIVE)"
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(multilineMessage)

        assertFalse("First line of rawText must not leak", guarded.contains("Confidential user note"))
        assertFalse("Spaced words must not leak", guarded.contains("with spaces"))
        assertFalse("Punctuation and second line must not leak", guarded.contains("with more private data"))
        assertTrue("Unrelated title property must be preserved", guarded.contains("title=SafeTitle"))
        assertTrue("Unrelated status property must be preserved", guarded.contains("status=ACTIVE"))
        assertTrue("Redaction tag must be present", guarded.contains("rawText=[REDACTED_BY_PRIVACY_GUARD]"))
    }

    @Test
    fun `guardAgainstMemoryLeakage redacts JSON and Bearer credentials`() {
        val jsonPayload = """{"rawText": "Secret note with spaces", "accessToken": "ya29.secret123", "status": "OK"}"""
        val guardedJson = PrivacySanitizer.guardAgainstMemoryLeakage(jsonPayload)
        assertFalse(guardedJson.contains("Secret note with spaces"))
        assertFalse(guardedJson.contains("ya29.secret123"))
        assertTrue(guardedJson.contains(""""status": "OK""""))

        val bearerHeader = "HTTP Request Header: Authorization: Bearer ya29.secretToken999999 for user"
        val guardedBearer = PrivacySanitizer.guardAgainstMemoryLeakage(bearerHeader)
        assertFalse(guardedBearer.contains("ya29.secretToken999999"))
        assertTrue(guardedBearer.contains("Bearer [REDACTED_BY_PRIVACY_GUARD]"))
    }

    @Test
    fun `sanitizeThrowable safely redacts messages and causes while preserving stack trace and class names`() {
        val rootCause = java.io.IOException("HTTP 401 https://api.notion.com/v1/oauth/token?code=notion_secret_code_xyz&client_secret=notion_sec_9999")
        val topException = RuntimeException("Failed processing MemoryItem(id=1, rawText=Patient medical notes with spaces, title=Medical)", rootCause)

        val sanitized = PrivacySanitizer.sanitizeThrowable(topException) as? SanitizedDiagnosticThrowable
        org.junit.Assert.assertNotNull(sanitized)
        assertEquals("java.lang.RuntimeException", sanitized!!.originalClassName)
        assertFalse("Top exception message must not leak rawText", sanitized.message!!.contains("Patient medical notes"))
        assertTrue("Top exception message must contain redacted tag", sanitized.message!!.contains("[REDACTED_BY_PRIVACY_GUARD]"))
        assertTrue("Diagnostic title must be preserved", sanitized.message!!.contains("title=Medical"))

        val sanitizedCause = sanitized.cause as? SanitizedDiagnosticThrowable
        org.junit.Assert.assertNotNull(sanitizedCause)
        assertEquals("java.io.IOException", sanitizedCause!!.originalClassName)
        assertFalse("Cause message must not leak code query parameter", sanitizedCause.message!!.contains("notion_secret_code_xyz"))
        assertFalse("Cause message must not leak client_secret query parameter", sanitizedCause.message!!.contains("notion_sec_9999"))
        assertTrue("Cause message must retain query param structure with redacted tag", sanitizedCause.message!!.contains("code=[REDACTED_BY_PRIVACY_GUARD]"))

        assertEquals("Stack trace depth must be preserved", topException.stackTrace.size, sanitized.stackTrace.size)
    }
}
