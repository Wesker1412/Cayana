package com.cayana.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.backup.crypto.InMemoryRecoveryKeyStorage
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.backup.drive.DriveAuthStatus
import com.cayana.backup.drive.FakeDriveAuthorizationManager
import com.cayana.backup.drive.FakeGoogleDriveBackupClient
import com.cayana.backup.manager.BackupManager
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.source.SourceExistence
import com.cayana.source.SourceExistenceValidator
import com.cayana.source.SourceType
import com.cayana.test.FakeCalendarProviderHelper
import com.cayana.test.FakePermissionChecker
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelBackupTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: CayanaDatabase
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var calendarProviderHelper: FakeCalendarProviderHelper
    private lateinit var driveClient: FakeGoogleDriveBackupClient
    private lateinit var driveAuthManager: FakeDriveAuthorizationManager
    private lateinit var recoveryKeyStorage: InMemoryRecoveryKeyStorage
    private lateinit var backupManager: BackupManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        val memoryRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        settingsRepository = InMemorySettingsRepository()
        permissionChecker = FakePermissionChecker()
        calendarProviderHelper = FakeCalendarProviderHelper()
        driveClient = FakeGoogleDriveBackupClient()
        driveAuthManager = FakeDriveAuthorizationManager(DriveAuthStatus.DISCONNECTED)
        recoveryKeyStorage = InMemoryRecoveryKeyStorage()

        val sourceValidator = object : SourceExistenceValidator(context) {
            override fun doesSourceExist(uriString: String?): Boolean = true
            override fun checkSourceExistence(uriString: String?, sourceType: SourceType?): SourceExistence = SourceExistence.Exists
        }

        backupManager = BackupManager(
            database = database,
            memoryDao = database.memoryDao(),
            calendarActionDao = database.calendarActionDao(),
            restoredCalendarActionHistoryDao = database.restoredCalendarActionHistoryDao(),
            memoryRepository = memoryRepo,
            settingsRepository = settingsRepository,
            driveClient = driveClient,
            driveAuthManager = driveAuthManager,
            recoveryKeyStorage = recoveryKeyStorage,
            sourceExistenceValidator = sourceValidator
        )

        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            permissionChecker = permissionChecker,
            calendarProviderHelper = calendarProviderHelper,
            backupManager = backupManager,
            driveAuthManager = driveAuthManager,
            recoveryKeyStorage = recoveryKeyStorage
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun connectGoogleDriveGeneratesRecoveryKeyAndPromptsDialog() = runTest {
        assertFalse(recoveryKeyStorage.hasRecoveryKey())

        var launchedSender: android.content.IntentSender? = null
        viewModel.startConnectDrive { sender ->
            launchedSender = sender
        }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Recovery key dialog must be shown on first connect", state.showRecoveryKeyDialog)
        assertNotNull("Generated key must not be null", state.generatedRecoveryKey)
        assertTrue(state.generatedRecoveryKey!!.contains("-"))

        // Confirm key
        viewModel.confirmRecoveryKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Dialog should be dismissed after confirmation", viewModel.uiState.value.showRecoveryKeyDialog)
        assertTrue("Key must now be saved in local storage", recoveryKeyStorage.hasRecoveryKey())
    }

    @Test
    fun performManualBackupUpdatesUiState() = runTest {
        // Setup root key and connect Drive
        val rootKey = RecoveryKeyManager.generateRootKey()
        recoveryKeyStorage.saveRecoveryKey(rootKey)
        driveAuthManager.onAuthorizationSuccess("valid_token")
        settingsRepository.updateDriveAuthStatus(DriveAuthStatus.CONNECTED)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.performManualBackup().join()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Backup state should be Success, was ${state.backupState}", state.backupState is BackupUiState.Success)

        // Simulate failure
        driveClient.uploadFailure = IOException("Drive storage full")
        viewModel.performManualBackup().join()
        testDispatcher.scheduler.advanceUntilIdle()

        val failState = viewModel.uiState.value
        assertTrue("Backup state should be Error on upload failure, was ${failState.backupState}", failState.backupState is BackupUiState.Error)
    }

    @Test
    fun disconnectGoogleDriveClearsTokenAndUpdatesStatus() = runTest {
        driveAuthManager.onAuthorizationSuccess("token")
        settingsRepository.updateDriveAuthStatus(DriveAuthStatus.CONNECTED)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.disconnectDrive()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DriveAuthStatus.DISCONNECTED, driveAuthManager.authStatus.value)
        assertEquals(DriveAuthStatus.DISCONNECTED, viewModel.uiState.value.settings.driveAuthStatus)
    }
}
