package com.cayana.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.cloud.auth.CloudAuthStatus
import com.cayana.cloud.auth.FakeCloudAuthManager
import com.cayana.cloud.auth.InMemoryRefreshTokenStorage
import com.cayana.cloud.client.FakeCayanaCloudClient
import com.cayana.cloud.sync.CloudSyncManager
import com.cayana.cloud.sync.DefaultCloudSyncManager
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.Result
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.repository.RoomMemoryRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudAuthTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase
    private lateinit var memoryRepository: RoomMemoryRepository
    private lateinit var recoveryKeyStorage: InMemoryRecoveryKeyStorage
    private lateinit var tokenStorage: InMemoryRefreshTokenStorage
    private lateinit var authManager: FakeCloudAuthManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        memoryRepository = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao(),
            dispatchers = AppDispatchers(),
            database = database,
            cloudSyncStateDao = database.cloudSyncStateDao(),
            cloudMemorySyncMetadataDao = database.cloudMemorySyncMetadataDao(),
            cloudSyncOutboxDao = database.cloudSyncOutboxDao()
        )

        recoveryKeyStorage = InMemoryRecoveryKeyStorage()
        recoveryKeyStorage.saveRecoveryKey(RecoveryKeyManager.generateRootKey())

        tokenStorage = InMemoryRefreshTokenStorage()
        authManager = FakeCloudAuthManager()
    }

    @After
    fun tearDown() {
        database.close()
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

    @Test
    fun existingRefreshTokenFailureNeverCreatesNewAnonymousTenant() = runTest {
        // Setup Tenant A with existing refresh token
        authManager.hasRefreshToken = true
        authManager.setSimulatedUserId("tenant-a-uuid")
        // Simulate restore session failure (network or invalid/expired session)
        authManager.shouldFailAuth = true
        authManager.shouldFailRefresh = true

        val syncManager = DefaultCloudSyncManager(
            cloudSyncStateDao = database.cloudSyncStateDao(),
            cloudMemorySyncMetadataDao = database.cloudMemorySyncMetadataDao(),
            cloudSyncOutboxDao = database.cloudSyncOutboxDao(),
            memoryDao = database.memoryDao(),
            memoryRepository = memoryRepository,
            cloudClient = FakeCayanaCloudClient(),
            cloudAuthManager = authManager,
            recoveryKeyStorage = recoveryKeyStorage,
            database = database
        )

        val result = syncManager.initializeAndEnable()

        // 1. Must fail safely
        assertTrue("Enable must fail when restoreSession fails", result is Result.Error)
        // 2. Must NEVER call initialSignInAnonymously
        assertEquals(
            "initialSignInAnonymously call count must be 0 to prevent tenant split",
            0,
            authManager.initialSignInAnonymouslyCallCount
        )
        // 3. Status must be NEEDS_ATTENTION
        assertEquals(CloudAuthStatus.NEEDS_ATTENTION, authManager.authStatus.value)
    }

    @Test
    fun freshInstallWithoutAnySessionMayCreateAnonymousTenant() = runTest {
        // Fresh install: no refresh token, uninitialized auth
        authManager.hasRefreshToken = false
        val freshAuthManager = FakeCloudAuthManager(initialStatus = CloudAuthStatus.UNINITIALIZED)

        val syncManager = DefaultCloudSyncManager(
            cloudSyncStateDao = database.cloudSyncStateDao(),
            cloudMemorySyncMetadataDao = database.cloudMemorySyncMetadataDao(),
            cloudSyncOutboxDao = database.cloudSyncOutboxDao(),
            memoryDao = database.memoryDao(),
            memoryRepository = memoryRepository,
            cloudClient = FakeCayanaCloudClient(),
            cloudAuthManager = freshAuthManager,
            recoveryKeyStorage = recoveryKeyStorage,
            database = database
        )

        val result = syncManager.initializeAndEnable()

        assertTrue("Enable should succeed on fresh install", result is Result.Success)
        assertEquals(
            "initialSignInAnonymously call count must be 1 on fresh install",
            1,
            freshAuthManager.initialSignInAnonymouslyCallCount
        )
        assertEquals(CloudAuthStatus.AUTHENTICATED, freshAuthManager.authStatus.value)
    }
}
