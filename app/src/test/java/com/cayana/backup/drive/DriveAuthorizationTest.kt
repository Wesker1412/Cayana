package com.cayana.backup.drive

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.config.BackupConfig
import com.cayana.core.common.Result
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveAuthorizationTest {

    private fun createAuthorizationResult(
        accessToken: String?,
        grantedScopes: List<String>?
    ): AuthorizationResult {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val result = allocateInstance.invoke(theUnsafe, AuthorizationResult::class.java) as AuthorizationResult

        for (field in AuthorizationResult::class.java.declaredFields) {
            field.isAccessible = true
            if (field.name == "zbb" || field.name.contains("accessToken", ignoreCase = true)) {
                field.set(result, accessToken)
            } else if (field.name == "zbd" || (List::class.java.isAssignableFrom(field.type) && field.name.contains("scope", ignoreCase = true))) {
                field.set(result, grantedScopes)
            }
        }
        return result
    }

    private fun createMockAuthClient(
        onGetResult: (Intent?) -> AuthorizationResult
    ): AuthorizationClient {
        return Proxy.newProxyInstance(
            AuthorizationClient::class.java.classLoader,
            arrayOf(AuthorizationClient::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAuthorizationResultFromIntent" -> onGetResult(args?.getOrNull(0) as? Intent)
                else -> null
            }
        } as AuthorizationClient
    }

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
    fun successfulAuthorizationUsesActualAccessToken() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedToken = "ya29.a0AfH6SM_actual_production_access_token_12345"
        val mockAuthResult = createAuthorizationResult(
            accessToken = expectedToken,
            grantedScopes = listOf(BackupConfig.DRIVE_SCOPE)
        )
        val mockClient = createMockAuthClient { mockAuthResult }
        val authManager = GoogleDriveAuthorizationManager(context) { mockClient }

        val testIntent = Intent().apply { putExtra("test_key", "test_val") }
        val handleResult = authManager.handleAuthorizationResult(testIntent)

        assertTrue("handleAuthorizationResult must succeed", handleResult is Result.Success)
        assertEquals(DriveAuthStatus.CONNECTED, authManager.authStatus.value)

        val tokenResult = authManager.getAccessToken()
        assertTrue("getAccessToken must succeed", tokenResult is Result.Success)
        assertEquals(expectedToken, (tokenResult as Result.Success).data)
    }

    @Test
    fun resultOkWithoutValidAuthorizationResultDoesNotConnect() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Return empty token from Intent
        val mockAuthResult = createAuthorizationResult(
            accessToken = "",
            grantedScopes = listOf(BackupConfig.DRIVE_SCOPE)
        )
        val mockClient = createMockAuthClient { mockAuthResult }
        val authManager = GoogleDriveAuthorizationManager(context) { mockClient }

        val testIntent = Intent()
        val handleResult = authManager.handleAuthorizationResult(testIntent)

        assertTrue("handleAuthorizationResult must fail with empty token", handleResult is Result.Error)
        assertEquals(DriveAuthStatus.DISCONNECTED, authManager.authStatus.value)

        val tokenResult = authManager.getAccessToken()
        assertTrue("Token must not be stored", tokenResult is Result.Error)
    }

    @Test
    fun authorizationMissingDriveAppDataScopeIsRejected() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Token present, but missing drive.appdata scope (e.g. only profile/email)
        val mockAuthResult = createAuthorizationResult(
            accessToken = "ya29.some_token_without_scope",
            grantedScopes = listOf("https://www.googleapis.com/auth/userinfo.email")
        )
        val mockClient = createMockAuthClient { mockAuthResult }
        val authManager = GoogleDriveAuthorizationManager(context) { mockClient }

        val testIntent = Intent()
        val handleResult = authManager.handleAuthorizationResult(testIntent)

        assertTrue("Missing drive.appdata scope must be rejected", handleResult is Result.Error)
        assertEquals(DriveAuthStatus.DISCONNECTED, authManager.authStatus.value)

        val tokenResult = authManager.getAccessToken()
        assertTrue("Token must not be accessible", tokenResult is Result.Error)
    }

    @Test
    fun accessTokenNeverPersisted() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val token = "super_secret_temporary_token_must_never_hit_disk_98765"
        val mockAuthResult = createAuthorizationResult(
            accessToken = token,
            grantedScopes = listOf(BackupConfig.DRIVE_SCOPE)
        )
        val mockClient = createMockAuthClient { mockAuthResult }
        val authManager = GoogleDriveAuthorizationManager(context) { mockClient }

        val testIntent = Intent()
        authManager.handleAuthorizationResult(testIntent)
        assertEquals(DriveAuthStatus.CONNECTED, authManager.authStatus.value)

        // Verify no SharedPreferences or DataStore file on disk contains the token
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (sharedPrefsDir.exists()) {
            sharedPrefsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val content = file.readText()
                assertFalse("Shared preference file ${file.name} must never contain access token", content.contains(token))
            }
        }
        val databasesDir = File(context.applicationInfo.dataDir, "databases")
        if (databasesDir.exists()) {
            databasesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val content = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse("Database file ${file.name} must never contain access token", content.contains(token))
            }
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
        fakeAuth.setAuthorizedToken("temporary-token-12345")
        assertEquals(DriveAuthStatus.CONNECTED, fakeAuth.authStatus.value)

        fakeAuth.disconnect()
        assertEquals(DriveAuthStatus.DISCONNECTED, fakeAuth.authStatus.value)

        val tokenResult = fakeAuth.getAccessToken()
        assertTrue(tokenResult is Result.Error)
    }

    @Test
    fun disconnectRevokesDriveAuthorization() = runTest {
        val fakeAuth = FakeDriveAuthorizationManager()
        fakeAuth.setAuthorizedToken("token_to_revoke")
        assertEquals(DriveAuthStatus.CONNECTED, fakeAuth.authStatus.value)

        val revokeResult = fakeAuth.revokeAuthorization()
        assertTrue(revokeResult is Result.Success)
        assertEquals(DriveAuthStatus.DISCONNECTED, fakeAuth.authStatus.value)
        assertEquals("token_to_revoke", fakeAuth.lastRevokedToken)
        assertNull(fakeAuth.fakeAccessToken)

        // When revoke fails, status is preserved
        fakeAuth.setAuthorizedToken("token_fails_revoke")
        fakeAuth.shouldRevokeFail = true
        val failResult = fakeAuth.revokeAuthorization()
        assertTrue(failResult is Result.Error)
        assertEquals("Status must not be marked DISCONNECTED if revocation fails", DriveAuthStatus.CONNECTED, fakeAuth.authStatus.value)
    }

    @Test
    fun disconnectAfterProcessRestartStillRevokesAuthorization() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val grantToken = "ya29.simulated_grant_token_after_process_restart"
        val mockAuthResult = createAuthorizationResult(
            accessToken = grantToken,
            grantedScopes = listOf(BackupConfig.DRIVE_SCOPE)
        )
        val mockClient = Proxy.newProxyInstance(
            AuthorizationClient::class.java.classLoader,
            arrayOf(AuthorizationClient::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "authorize" -> com.google.android.gms.tasks.Tasks.forResult(mockAuthResult)
                else -> null
            }
        } as AuthorizationClient

        MockHttpServer().use { server ->
            val revokedTokenReceived = java.util.concurrent.atomic.AtomicReference<String>()
            server.on({ method, path -> method == "POST" && path.startsWith("/revoke") }) { req ->
                val match = Regex("""token=([^&]+)""").find(req.path)
                revokedTokenReceived.set(match?.groupValues?.get(1))
                MockResponse(200, emptyMap(), "{}")
            }

            val authManager = GoogleDriveAuthorizationManager(
                context = context,
                authClientProvider = { mockClient },
                revokeUrlBase = "http://127.0.0.1:${server.port}/revoke"
            )

            // inMemoryAccessToken is null initially
            val revokeResult = authManager.revokeAuthorization()
            assertTrue("Revocation must succeed", revokeResult is Result.Success)
            assertEquals(DriveAuthStatus.DISCONNECTED, authManager.authStatus.value)
            assertEquals("Silent authorization must obtain grant token and pass to revoke endpoint", grantToken, revokedTokenReceived.get())
        }
    }

    @Test
    fun revokeHttp400DoesNotReportDisconnected() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val grantToken = "ya29.token_for_failing_revoke"
        val mockAuthResult = createAuthorizationResult(
            accessToken = grantToken,
            grantedScopes = listOf(BackupConfig.DRIVE_SCOPE)
        )
        val mockClient = Proxy.newProxyInstance(
            AuthorizationClient::class.java.classLoader,
            arrayOf(AuthorizationClient::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "authorize" -> com.google.android.gms.tasks.Tasks.forResult(mockAuthResult)
                else -> null
            }
        } as AuthorizationClient

        MockHttpServer().use { server ->
            server.on({ method, path -> method == "POST" && path.startsWith("/revoke") }) { _ ->
                MockResponse(400, emptyMap(), "{\"error\":\"invalid_request\"}")
            }

            val authManager = GoogleDriveAuthorizationManager(
                context = context,
                authClientProvider = { mockClient },
                revokeUrlBase = "http://127.0.0.1:${server.port}/revoke"
            )

            val revokeResult = authManager.revokeAuthorization()
            assertTrue("Revocation with HTTP 400 must return Error", revokeResult is Result.Error)
            assertFalse(
                "Status must never be marked DISCONNECTED if revoke returned HTTP 400",
                authManager.authStatus.value == DriveAuthStatus.DISCONNECTED
            )
        }
    }

    @Test
    fun silentAuthorizationWithoutTokenOrScopeFailsAndDoesNotConnect() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val emptyTokenResult = createAuthorizationResult(
            accessToken = "",
            grantedScopes = listOf(BackupConfig.DRIVE_SCOPE)
        )
        val mockClient = Proxy.newProxyInstance(
            AuthorizationClient::class.java.classLoader,
            arrayOf(AuthorizationClient::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "authorize" -> com.google.android.gms.tasks.Tasks.forResult(emptyTokenResult)
                else -> null
            }
        } as AuthorizationClient

        val authManager = GoogleDriveAuthorizationManager(context) { mockClient }
        val intentSenderResult = authManager.getAuthorizationIntentSender()

        assertTrue("Intent sender query without valid token must return Error", intentSenderResult is Result.Error)
        assertEquals(DriveAuthStatus.AUTH_REQUIRED, authManager.authStatus.value)
    }
}
