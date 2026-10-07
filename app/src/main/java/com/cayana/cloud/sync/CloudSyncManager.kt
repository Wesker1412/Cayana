package com.cayana.cloud.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cayana.backup.crypto.RecoveryKeyStorage
import com.cayana.cloud.auth.CloudAuthManager
import com.cayana.cloud.client.CayanaCloudClient
import com.cayana.cloud.client.CloudRemoteRecord
import com.cayana.cloud.config.CloudConfig
import com.cayana.cloud.crypto.CloudCryptoService
import com.cayana.cloud.crypto.EncryptedCloudRecord
import com.cayana.cloud.crypto.toCloudPayload
import com.cayana.cloud.crypto.toDomain
import com.cayana.cloud.data.CloudMemorySyncMetadataDao
import com.cayana.cloud.data.CloudMemorySyncMetadataEntity
import com.cayana.cloud.data.CloudSyncOutboxDao
import com.cayana.cloud.data.CloudSyncOutboxEntity
import com.cayana.cloud.data.CloudSyncStateDao
import com.cayana.cloud.data.CloudSyncStateEntity
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.repository.MemoryRepository
import com.cayana.memory.repository.MutationOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class RecoveryKeyRequiredException(message: String = "尚未設定 Cayana 復原金鑰") : Exception(message)

data class SyncSummary(
    val pushedCount: Int,
    val pulledCount: Int
)

interface CloudSyncManager {
    val syncStateFlow: Flow<CloudSyncStateEntity?>
    suspend fun getSyncState(): CloudSyncStateEntity?
    suspend fun initializeAndEnable(context: Context? = null): Result<Unit>
    suspend fun disable(context: Context? = null): Result<Unit>
    suspend fun syncOnce(): Result<SyncSummary>
}

class DefaultCloudSyncManager(
    private val cloudSyncStateDao: CloudSyncStateDao,
    private val cloudMemorySyncMetadataDao: CloudMemorySyncMetadataDao,
    private val cloudSyncOutboxDao: CloudSyncOutboxDao,
    private val memoryDao: MemoryDao,
    private val memoryRepository: MemoryRepository,
    private val cloudClient: CayanaCloudClient,
    private val cloudAuthManager: CloudAuthManager,
    private val recoveryKeyStorage: RecoveryKeyStorage
) : CloudSyncManager {

    private val syncMutex = Mutex()

    override val syncStateFlow: Flow<CloudSyncStateEntity?> = cloudSyncStateDao.observeSyncState()

    override suspend fun getSyncState(): CloudSyncStateEntity? = withContext(Dispatchers.IO) {
        cloudSyncStateDao.getSyncState()
    }

    override suspend fun initializeAndEnable(context: Context?): Result<Unit> = withContext(Dispatchers.IO) {
        // 1. Ensure recovery root key is present
        if (!recoveryKeyStorage.hasRecoveryKey()) {
            return@withContext Result.Error(RecoveryKeyRequiredException())
        }
        val rootKey = recoveryKeyStorage.loadRecoveryKey()
            ?: return@withContext Result.Error(RecoveryKeyRequiredException("無法載入復原金鑰。"))

        // 2. Ensure anonymous cloud tenant is authenticated
        val authResult = cloudAuthManager.getValidAccessToken()
        if (authResult !is Result.Success) {
            val signInResult = cloudAuthManager.initialSignInAnonymously()
            if (signInResult !is Result.Success) {
                val exception = (signInResult as? Result.Error)?.exception ?: IllegalStateException("無法建立匿名連線")
                cloudSyncStateDao.recordSyncError("驗證失敗：${exception.message}")
                return@withContext Result.Error(exception)
            }
        }

        // 3. Initialize cloud sync state in DB
        val existingState = cloudSyncStateDao.getSyncState()
        val newState = CloudSyncStateEntity(
            id = 1,
            isInitialized = true,
            isEnabled = true,
            lastPullSeq = existingState?.lastPullSeq ?: 0L,
            lastSuccessfulSyncAt = existingState?.lastSuccessfulSyncAt ?: 0L,
            lastErrorCode = null
        )
        cloudSyncStateDao.upsertSyncState(newState)

        // 4. Scan current canonical memories, assign initial revision if absent, enqueue initial UPSERT outbox
        val allMemories = memoryDao.getAllMemoriesDirect()
        val initialOutboxItems = mutableListOf<CloudSyncOutboxEntity>()

        for (mem in allMemories) {
            val meta = cloudMemorySyncMetadataDao.getMetadata(mem.id)
            if (meta == null || meta.revision == 0L) {
                cloudMemorySyncMetadataDao.upsertMetadata(
                    CloudMemorySyncMetadataEntity(
                        memoryId = mem.id,
                        revision = 1L,
                        lastSyncedRevision = 0L
                    )
                )
                initialOutboxItems.add(
                    CloudSyncOutboxEntity(
                        id = UUID.randomUUID().toString(),
                        memoryId = mem.id,
                        revision = 1L,
                        operation = "UPSERT",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
        if (initialOutboxItems.isNotEmpty()) {
            cloudSyncOutboxDao.enqueueAll(initialOutboxItems)
        }

        // 5. Schedule WorkManager periodic sync if context provided
        context?.let { schedulePeriodicSync(it) }

        CayanaLogger.i("CloudSync", "Cloud sync initialized and enabled successfully")
        Result.Success(Unit)
    }

    override suspend fun disable(context: Context?): Result<Unit> = withContext(Dispatchers.IO) {
        cloudSyncStateDao.setSyncEnabled(false)
        context?.let { cancelPeriodicSync(it) }
        CayanaLogger.i("CloudSync", "Cloud sync disabled")
        Result.Success(Unit)
    }

    override suspend fun syncOnce(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val state = cloudSyncStateDao.getSyncState()
            if (state == null || !state.isInitialized || !state.isEnabled) {
                return@withContext Result.Success(SyncSummary(0, 0))
            }

            val rootKey = recoveryKeyStorage.loadRecoveryKey()
                ?: return@withContext Result.Error(RecoveryKeyRequiredException())
            val cloudSyncKey = CloudCryptoService.deriveCloudSyncKey(rootKey)

            var totalPushed = 0
            var totalPulled = 0

            // -------------------------------------------------------------
            // Step 1: Drain Outbox (Push)
            // -------------------------------------------------------------
            var hadPushError = false
            while (true) {
                val pendingBatch = cloudSyncOutboxDao.getPendingBatch(CloudConfig.MAX_OUTBOX_BATCH_SIZE)
                if (pendingBatch.isEmpty()) break

                var anyErrorInBatch = false
                for (item in pendingBatch) {
                    val record: EncryptedCloudRecord = if (item.operation == "UPSERT") {
                        val memoryEntity = memoryDao.getMemoryById(item.memoryId)
                        if (memoryEntity != null) {
                            val domain = memoryEntity.toDomain()
                            CloudCryptoService.encryptMemory(domain.toCloudPayload(), item.revision, cloudSyncKey)
                        } else {
                            // Memory was deleted before sync was completed: emit authenticated tombstone
                            CloudCryptoService.encryptTombstone(item.memoryId, item.revision, cloudSyncKey)
                        }
                    } else {
                        // DELETE operation
                        CloudCryptoService.encryptTombstone(item.memoryId, item.revision, cloudSyncKey)
                    }

                    when (val upsertResult = cloudClient.upsertRecord(record)) {
                        is Result.Success -> {
                            val resp = upsertResult.data
                            if (resp.status == "ACCEPTED" || resp.status == "IDEMPOTENT" || resp.status == "STALE") {
                                cloudMemorySyncMetadataDao.updateLastSyncedRevision(item.memoryId, item.revision)
                                cloudSyncOutboxDao.deleteById(item.id)
                                totalPushed++
                            }
                        }
                        is Result.Error -> {
                            anyErrorInBatch = true
                            hadPushError = true
                            cloudSyncOutboxDao.incrementAttemptCount(item.id)
                            cloudSyncStateDao.recordSyncError(upsertResult.exception.message)
                            CayanaLogger.w("CloudSync", "Failed to push outbox item ${item.id}", upsertResult.exception)
                            break
                        }
                        Result.Loading -> {}
                    }
                }
                if (anyErrorInBatch) break
            }

            // -------------------------------------------------------------
            // Step 2: Pull Remote Changes (Pull)
            // -------------------------------------------------------------
            var currentPullSeq = cloudSyncStateDao.getSyncState()?.lastPullSeq ?: 0L

            var hadBatchError = false
            while (true) {
                val pullResult = cloudClient.pullRecords(afterSeq = currentPullSeq, limit = CloudConfig.MAX_PULL_BATCH_SIZE)
                if (pullResult !is Result.Success) {
                    val exception = (pullResult as? Result.Error)?.exception ?: IllegalStateException("Pull failed")
                    cloudSyncStateDao.recordSyncError(exception.message)
                    return@withContext Result.Error(exception)
                }

                val remoteBatch = pullResult.data
                if (remoteBatch.isEmpty()) break

                var batchSuccess = true
                for (remoteRecord in remoteBatch) {
                    // 1. Structural validation
                    if (remoteRecord.payload_version != CloudCryptoService.PAYLOAD_VERSION_V1) {
                        cloudSyncStateDao.recordSyncError("不支援的 Cloud payload 版本：${remoteRecord.payload_version}")
                        batchSuccess = false
                        break
                    }
                    if (remoteRecord.nonce.isBlank() || remoteRecord.ciphertext.isBlank()) {
                        cloudSyncStateDao.recordSyncError("遠端記錄缺少 nonce 或 ciphertext")
                        batchSuccess = false
                        break
                    }

                    // 2. Cryptographic decryption and application
                    try {
                        val encryptedRecord = EncryptedCloudRecord(
                            memoryId = remoteRecord.memory_id,
                            revision = remoteRecord.revision,
                            payloadVersion = remoteRecord.payload_version,
                            nonceBase64 = remoteRecord.nonce,
                            ciphertextBase64 = remoteRecord.ciphertext,
                            isTombstone = remoteRecord.is_tombstone
                        )

                        if (remoteRecord.is_tombstone) {
                            val tombstone = CloudCryptoService.decryptTombstone(encryptedRecord, cloudSyncKey)
                            val localMeta = cloudMemorySyncMetadataDao.getMetadata(tombstone.memoryId)
                            val localRev = localMeta?.revision ?: 0L

                            if (remoteRecord.revision > localRev) {
                                // Remote tombstone deletes local canonical memory only (never original media/calendar)
                                memoryRepository.deleteMemory(
                                    id = tombstone.memoryId,
                                    origin = MutationOrigin.CLOUD_SYNC,
                                    remoteRevision = remoteRecord.revision
                                )
                            }
                        } else {
                            val payload = CloudCryptoService.decryptMemory(encryptedRecord, cloudSyncKey)
                            val localMeta = cloudMemorySyncMetadataDao.getMetadata(payload.id)
                            val localRev = localMeta?.revision ?: 0L

                            if (remoteRecord.revision > localRev) {
                                val domain = payload.toDomain()
                                memoryRepository.saveMemory(
                                    item = domain,
                                    origin = MutationOrigin.CLOUD_SYNC,
                                    remoteRevision = remoteRecord.revision
                                )
                            }
                        }

                        // Advance cursor for each successfully applied record
                        currentPullSeq = remoteRecord.change_seq
                        cloudSyncStateDao.updateLastPullSeq(currentPullSeq)
                        totalPulled++
                    } catch (e: Exception) {
                        CayanaLogger.w("CloudSync", "Failed to apply remote record ${remoteRecord.memory_id}", e)
                        cloudSyncStateDao.recordSyncError("解密或套用遠端記錄失敗：${e.message}")
                        batchSuccess = false
                        break
                    }
                }

                if (!batchSuccess) {
                    // Do not advance cursor past unhandled corrupted record
                    hadBatchError = true
                    break
                }
            }

            if (hadPushError || hadBatchError) {
                val state = cloudSyncStateDao.getSyncState()
                return@withContext Result.Error(IllegalStateException(state?.lastErrorCode ?: "Cloud sync failed"))
            }

            cloudSyncStateDao.recordSuccessfulSync(System.currentTimeMillis())
            Result.Success(SyncSummary(pushedCount = totalPushed, pulledCount = totalPulled))
        }
    }

    companion object {
        const val PERIODIC_SYNC_WORK_NAME = "cayana_cloud_sync_periodic"

        fun schedulePeriodicSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val syncRequest = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_SYNC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    syncRequest
                )
            } catch (e: Exception) {
                CayanaLogger.w("CloudSync", "Failed to schedule periodic sync work: ${e.message}")
            }
        }

        fun cancelPeriodicSync(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
            } catch (e: Exception) {
                CayanaLogger.w("CloudSync", "Failed to cancel periodic sync work: ${e.message}")
            }
        }
    }
}
