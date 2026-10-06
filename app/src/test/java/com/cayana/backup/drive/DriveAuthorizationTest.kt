package com.cayana.backup.drive

import com.cayana.backup.config.BackupConfig
import com.cayana.core.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthorizationTest {

    @Test
    fun authorizationRequestContainsOnlyDriveAppDataScope() {
        val request = GoogleDriveAuthorizationManager.buildAuthorizationRequest()
        val scopes = request.requestedScopes

        assertEquals(1, scopes.size)
        val requestedScopeUri = scopes.first().scopeUri
        assertEquals(BackupConfig.DRIVE_SCOPE, requestedScopeUri)
        assertEquals("https://www.googleapis.com/auth/drive.appdata", requestedScopeUri)

        // Verify strictly excluded scopes
        val forbiddenScopes = listOf(
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.file",
            "https://www.googleapis.com/auth/drive.readonly",
            "email",
            "profile",
            "openid"
        )
        for (forbidden in forbiddenScopes) {
            assertFalse("Scope list must not contain $forbidden", scopes.any { it.scopeUri == forbidden })
        }
    }

    @Test
    fun backgroundWorkerResolutionRequiredDoesNotLaunchUiAndMarksAuthRequired() = runTest {
        val fakeAuth = FakeDriveAuthorizationManager()
        fakeAuth.requireResolution = true

        val tokenResult = fakeAuth.getAccessToken()
        assertTrue(tokenResult is Result.Error)
        assertEquals(DriveAuthStatus.AUTH_REQUIRED, fakeAuth.authStatus.value)
    }

    @Test
    fun disconnectClearsAccessTokenInMemory() = runTest {
        val fakeAuth = FakeDriveAuthorizationManager()
        fakeAuth.onAuthorizationSuccess("temporary-token-12345")
        assertEquals(DriveAuthStatus.CONNECTED, fakeAuth.authStatus.value)

        fakeAuth.disconnect()
        assertEquals(DriveAuthStatus.DISCONNECTED, fakeAuth.authStatus.value)

        val tokenResult = fakeAuth.getAccessToken()
        assertTrue(tokenResult is Result.Error)
    }
}
