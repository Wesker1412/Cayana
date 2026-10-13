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
        val multilineMessage = "Event MemoryItem(id=99, title=SafeTitle, rawText=Confidential user note with spaces, punctuation! And a second line\nwith more private data., status=ACTIVE)"
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(multilineMessage)

        assertFalse("First line of rawText must not leak", guarded.contains("Confidential user note"))
        assertFalse("Spaced words must not leak", guarded.contains("with spaces"))
        assertFalse("Punctuation and second line must not leak", guarded.contains("with more private data"))
        assertTrue("Preceding id property must be preserved", guarded.contains("id=99"))
        assertTrue("Preceding title property must be preserved", guarded.contains("title=SafeTitle"))
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
    fun `guardAgainstMemoryLeakage fail-closed when rawText contains parentheses and subsequent private text`() {
        val message = "Event rawText=The diagnosis is (confidential) and HIV positive, title=Example"
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(message)

        assertFalse("Parenthesized confidential text must not leak", guarded.contains("(confidential)"))
        assertFalse("Text after closing parenthesis must not leak", guarded.contains("and HIV positive"))
        assertFalse("HIV diagnosis keyword must not leak", guarded.contains("HIV"))
        assertTrue("Redaction tag must be present", guarded.contains("rawText=[REDACTED_BY_PRIVACY_GUARD]"))
    }

    @Test
    fun `guardAgainstMemoryLeakage fail-closed when rawText contains square brackets and subsequent private text`() {
        val message = "MemoryItem(id=2, rawText=The results [Confidential: positive for diabetes] patient Jane, title=Medical)"
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(message)

        assertFalse("Bracketed text must not leak", guarded.contains("Confidential: positive for diabetes"))
        assertFalse("Text after bracket must not leak", guarded.contains("patient Jane"))
        assertFalse("Disease keyword must not leak", guarded.contains("diabetes"))
        assertTrue("Redaction tag must be present", guarded.contains("rawText=[REDACTED_BY_PRIVACY_GUARD]"))
    }

    @Test
    fun `guardAgainstMemoryLeakage fail-closed when rawText contains multiline commas and simulated fields`() {
        val message = "Processing rawText=Line 1, and title=SimulatedField, and status=ACTIVE\nLine 2 contains cancer treatment info, title=RealTitle)"
        val guarded = PrivacySanitizer.guardAgainstMemoryLeakage(message)

        assertFalse("Simulated field in private text must not fool parser", guarded.contains("SimulatedField"))
        assertFalse("Second line after simulated field must not leak", guarded.contains("Line 2 contains cancer treatment info"))
        assertFalse("Cancer medical info must not leak", guarded.contains("cancer treatment info"))
        assertTrue("Redaction tag must be present", guarded.contains("rawText=[REDACTED_BY_PRIVACY_GUARD]"))
    }

    @Test
    fun `guardAgainstMemoryLeakage handles escaped quotes in JSON sensitive fields`() {
        val jsonPayload = """{"id": "1", "rawText": "Patient said \"I am HIV positive\" in private", "status": "SAVED"}"""
        val guardedJson = PrivacySanitizer.guardAgainstMemoryLeakage(jsonPayload)

        assertFalse("Escaped quote text must not leak", guardedJson.contains("I am HIV positive"))
        assertFalse("HIV keyword must not leak", guardedJson.contains("HIV"))
        assertTrue("Subsequent uncorrupted JSON field must be preserved", guardedJson.contains(""""status": "SAVED""""))
        assertTrue("Redaction tag must be present", guardedJson.contains(""""rawText": "[REDACTED_BY_PRIVACY_GUARD]""""))
    }

    @Test
    fun `sanitizeThrowable fail-closed redacts arbitrary exception message without field names`() {
        val ex = RuntimeException("Patient John Doe (DOB 1980-01-01) diagnosed with positive HIV and cancer")
        val sanitized = PrivacySanitizer.sanitizeThrowable(ex) as? SanitizedDiagnosticThrowable

        org.junit.Assert.assertNotNull(sanitized)
        assertEquals("java.lang.RuntimeException", sanitized!!.originalClassName)
        assertFalse("Name in arbitrary exception message must not leak", sanitized.message!!.contains("John Doe"))
        assertFalse("DOB in arbitrary exception message must not leak", sanitized.message!!.contains("1980-01-01"))
        assertFalse("HIV diagnosis in arbitrary exception message must not leak", sanitized.message!!.contains("HIV"))
        assertFalse("Cancer diagnosis in arbitrary exception message must not leak", sanitized.message!!.contains("cancer"))
        assertTrue("Exception message must be fail-closed redacted", sanitized.message!!.contains("[REDACTED_EXCEPTION_MESSAGE]"))
        assertEquals("Stack trace depth must be preserved", ex.stackTrace.size, sanitized.stackTrace.size)
    }

    @Test
    fun `sanitizeThrowable preserves whitelisted safe diagnostic codes and HTTP status`() {
        val timeoutEx = java.net.SocketTimeoutException("connect timed out")
        val sanitizedTimeout = PrivacySanitizer.sanitizeThrowable(timeoutEx) as? SanitizedDiagnosticThrowable
        org.junit.Assert.assertNotNull(sanitizedTimeout)
        assertTrue(sanitizedTimeout!!.message!!.contains("connect timed out"))

        val httpEx = java.io.IOException("Failed with HTTP 401 from server")
        val sanitizedHttp = PrivacySanitizer.sanitizeThrowable(httpEx) as? SanitizedDiagnosticThrowable
        org.junit.Assert.assertNotNull(sanitizedHttp)
        assertTrue(sanitizedHttp!!.message!!.contains("HTTP 401"))

        val structuredEx = IllegalStateException("AUTH_REQUIRED")
        val sanitizedStructured = PrivacySanitizer.sanitizeThrowable(structuredEx) as? SanitizedDiagnosticThrowable
        org.junit.Assert.assertNotNull(sanitizedStructured)
        assertTrue(sanitizedStructured!!.message!!.contains("AUTH_REQUIRED"))
    }
}
