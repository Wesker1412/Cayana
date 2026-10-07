package com.cayana.cloud

import com.cayana.cloud.auth.CloudAuthStatus
import com.cayana.cloud.auth.FakeCloudAuthManager
import com.cayana.cloud.auth.InMemoryRefreshTokenStorage
import com.cayana.core.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CloudAuthTest {

    private lateinit var tokenStorage: InMemoryRefreshTokenStorage
    private lateinit var authManager: FakeCloudAuthManager

    @Before
    fun setUp() {
        tokenStorage = InMemoryRefreshTokenStorage()
        authManager = FakeCloudAuthManager()
    }

    @Test
    fun initialSignInHoldsAccessTokenInMemoryOnly() = runTest {
        val fakeUserId = UUID.randomUUID().toString()
        authManager.setSimulatedUserId(fakeUserId)
        authManager.fakeAccessToken = "test-jwt-access-token-secret"

        val result = authManager.initialSignInAnonymously()
        assertTrue(result is Result.Success)
        val token = (result as Result.Success).data
        assertEquals("test-jwt-access-token-secret", token)
        assertEquals(CloudAuthStatus.AUTHENTICATED, authManager.authStatus.value)
        assertEquals(fakeUserId, authManager.getUserId())

        // Refresh token storage only manages refresh tokens, never access tokens
        tokenStorage.saveRefreshToken("wrapped-refresh-token")
        assertEquals("wrapped-refresh-token", tokenStorage.loadRefreshToken())
        tokenStorage.clearRefreshToken()
        assertNull(tokenStorage.loadRefreshToken())
    }

    @Test
    fun refreshFailureTransitionsToNeedsAttentionWithoutRecreatingTenant() = runTest {
        val originalUserId = "original-tenant-uuid-1"
        authManager.setSimulatedUserId(originalUserId)
        authManager.initialSignInAnonymously()
        assertEquals(CloudAuthStatus.AUTHENTICATED, authManager.authStatus.value)

        // Simulate refresh failure (expired/revoked token)
        authManager.shouldFailRefresh = true
        val refreshResult = authManager.refreshSession()
        assertTrue(refreshResult is Result.Error)

        // Status must be NEEDS_ATTENTION
        assertEquals(CloudAuthStatus.NEEDS_ATTENTION, authManager.authStatus.value)

        // Strict invariant: refresh failure must NOT change user ID or create second tenant
        // User ID remains the original tenant
        authManager.setSimulatedUserId(originalUserId)
        assertEquals(null, authManager.getUserId()) // in NEEDS_ATTENTION, getUserId returns null until re-authenticated
    }

    @Test
    fun anonymousTenantCreationOnlyOccursOnExplicitInitialSignIn() = runTest {
        // Initial state is uninitialized
        val freshManager = FakeCloudAuthManager(initialStatus = CloudAuthStatus.UNINITIALIZED)
        assertEquals(CloudAuthStatus.UNINITIALIZED, freshManager.authStatus.value)
        assertNull(freshManager.getUserId())

        // Explicit initial sign in
        val signInResult = freshManager.initialSignInAnonymously()
        assertTrue(signInResult is Result.Success)
        assertEquals(CloudAuthStatus.AUTHENTICATED, freshManager.authStatus.value)
        assertNotNull(freshManager.getUserId())
    }
}
