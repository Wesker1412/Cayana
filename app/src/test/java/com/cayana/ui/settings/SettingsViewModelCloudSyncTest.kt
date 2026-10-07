package com.cayana.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.cloud.auth.FakeCloudAuthManager
import com.cayana.cloud.client.FakeCayanaCloudClient
import com.cayana.cloud.data.CloudSyncStateDao
import com.cayana.cloud.data.CloudSyncStateEntity
import com.cayana.cloud.sync.CloudSyncManager
import com.cayana.cloud.sync.DefaultCloudSyncManager
import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.memory.repository.MutationOrigin
import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelCloudSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var calendarProviderHelper: FakeCalendarProviderHelper
    private lateinit var recoveryKeyStorage: InMemoryRecoveryKeyStorage
    private lateinit var fakeCloudClient: FakeCayanaCloudClient
    private lateinit var fakeAuthManager: FakeCloudAuthManager
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var viewModel: SettingsViewModel

    private val stateFlow = MutableStateFlow<CloudSyncStateEntity?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = InMemorySettingsRepository()
        permissionChecker = FakePermissionChecker()
        calendarProviderHelper = FakeCalendarProviderHelper()
        recoveryKeyStorage = InMemoryRecoveryKeyStorage()
        fakeCloudClient = FakeCayanaCloudClient()
        fakeAuthManager = FakeCloudAuthManager()

        val mockSyncManager = object : CloudSyncManager {
            override val syncStateFlow: Flow<CloudSyncStateEntity?> = stateFlow
            override suspend fun getSyncState(): CloudSyncStateEntity? = stateFlow.value
            override suspend fun initializeAndEnable(context: Context?): com.cayana.core.common.Result<Unit> {
                if (!recoveryKeyStorage.hasRecoveryKey()) {
                    return com.cayana.core.common.Result.Error(IllegalStateException("No recovery key"))
                }
                stateFlow.value = CloudSyncStateEntity(id = 1, isInitialized = true, isEnabled = true)
                return com.cayana.core.common.Result.Success(Unit)
            }
            override suspend fun disable(context: Context?): com.cayana.core.common.Result<Unit> {
                stateFlow.value = stateFlow.value?.copy(isEnabled = false)
                return com.cayana.core.common.Result.Success(Unit)
            }
            override suspend fun syncOnce(): com.cayana.core.common.Result<com.cayana.cloud.sync.SyncSummary> {
                return com.cayana.core.common.Result.Success(com.cayana.cloud.sync.SyncSummary(1, 1))
            }
        }
        cloudSyncManager = mockSyncManager

        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper,
            recoveryKeyStorage = recoveryKeyStorage,
            cloudSyncManager = cloudSyncManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun enablingCloudSyncWithoutRecoveryKeyPromptsDialog() = runTest {
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.settings.hasRecoveryKey)

        viewModel.toggleCloudSync(true, context)
        advanceUntilIdle()

        // Must display recovery key creation dialog
        assertTrue(viewModel.uiState.value.showRecoveryKeyDialog)
        assertNotNull(viewModel.uiState.value.generatedRecoveryKey)
        assertFalse("Cloud sync should not be enabled yet", viewModel.uiState.value.cloudSyncEnabled)
    }

    @Test
    fun enablingCloudSyncWithRecoveryKeySucceeds() = runTest {
        // Set up recovery key
        val rootKey = RecoveryKeyManager.generateRootKey()
        recoveryKeyStorage.saveRecoveryKey(rootKey)
        settingsRepository.updateHasRecoveryKey(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.settings.hasRecoveryKey)

        viewModel.toggleCloudSync(true, context)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.cloudSyncEnabled)
        assertEquals(CloudSyncUiStatus.SYNCED, viewModel.uiState.value.cloudSyncStatus)
    }

    @Test
    fun disablingCloudSyncUpdatesUiState() = runTest {
        val rootKey = RecoveryKeyManager.generateRootKey()
        recoveryKeyStorage.saveRecoveryKey(rootKey)
        settingsRepository.updateHasRecoveryKey(true)
        advanceUntilIdle()

        viewModel.toggleCloudSync(true, context)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.cloudSyncEnabled)

        viewModel.toggleCloudSync(false, context)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.cloudSyncEnabled)
    }

    @Test
    fun performCloudSyncNowTransitionsStatus() = runTest {
        viewModel.performCloudSyncNow().join()
        advanceUntilIdle()

        assertEquals(CloudSyncUiStatus.SYNCED, viewModel.uiState.value.cloudSyncStatus)
        assertEquals(null, viewModel.uiState.value.cloudSyncError)
    }
}
