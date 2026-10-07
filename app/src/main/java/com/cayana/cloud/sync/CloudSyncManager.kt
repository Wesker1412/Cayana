package com.cayana.cloud.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.room.withTransaction
import com.cayana.backup.crypto.RecoveryKeyStorage
import com.cayana.cloud.auth.CloudAuthManager
import com.cayana.cloud.auth.CloudAuthStatus
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
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.data.MemoryDao
import com.cayana.memory.repository.MemoryRepository
import com.cayana.memory.repository.MutationOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64
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
    private val recoveryKeyStorage: RecoveryKeyStorage,
    private val database: CayanaDatabase? = null
) : CloudSyncManager {

    private val syncMutex = Mutex()

    override val syncStateFlow: Flow<CloudSyncStateEntity?> = cloudSyncStateDao.observeSyncState()

    override suspend fun getSyncState(): CloudSyncStateEntity? = withContext(Dispatchers.IO) {
        cloudSyncStateDao.getSyncState()
    }

    override suspend fun initializeAndEnable(context: Context?): Result<Unit> = withContext(Dispatchers.IO) {
        // 0. Ensure cloud backend configuration if using live client
        if (cloudClient is com.cayana.cloud.client.SupabaseCayanaCloudClient && !CloudConfig.isConfigured()) {
            cloudSyncStateDao.recordSyncError("Cayana Cloud 尚未配置伺服器位址或金鑰")
            return@withContext Result.Error(IllegalStateException("Cayana Cloud 尚未設定"))
        }

        // 1. Ensure recovery root key is present
        if (!recoveryKeyStorage.hasRecoveryKey()) {
            return@withContext Result.Error(RecoveryKeyRequiredException())
        }
        val rootKey = recoveryKeyStorage.loadRecoveryKey()
            ?: return@withContext Result.Error(RecoveryKeyRequiredException("無法載入復原金鑰。"))

        // 2. Ensure anonymous cloud tenant is authenticated
        // Rule: If refresh token exists, NEVER call initialSignInAnonymously() to avoid tenant splitting.
        if (cloudAuthManager.hasStoredRefreshToken()) {
            val tokenResult = cloudAuthManager.getValidAccessToken()
            if (tokenResult !is Result.Success) {
                val exception = (tokenResult as? Result.Error)?.exception ?: IllegalStateException("無法恢復既有雲端連線")
                cloudSyncStateDao.recordSyncError("連線失效：${exception.message}")
                return@withContext Result.Error(exception)
            }
        } else {
            // Truly fresh install without any session: create anonymous tenant
            if (cloudAuthManager.authStatus.value == CloudAuthStatus.UNINITIALIZED) {
                val signInResult = cloudAuthManager.initialSignInAnonymously()
                if (signInResult !is Result.Success) {
                    val exception = (signInResult as? Result.Error)?.exception ?: IllegalStateException("無法建立匿名連線")
                    cloudSyncStateDao.recordSyncError("驗證失敗：${exception.message}")
                    return@withContext Result.Error(exception)
                }
            } else {
                val tokenResult = cloudAuthManager.getValidAccessToken()
                if (tokenResult !is Result.Success) {
                    val exception = (tokenResult as? Result.Error)?.exception ?: IllegalStateException("無法取得存取權杖")
                    cloudSyncStateDao.recordSyncError("驗證失敗：${exception.message}")
                    return@withContext Result.Error(exception)
                }
            }
        }

        // 3. Atomically initialize cloud sync state, metadata, and outbox in single Room transaction
        val performDbInit: suspend () -> Unit = {
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
        }

        if (database != null) {
            database.withTransaction {
                performDbInit()
            }
        } else {
            performDbInit()
        }

        // 4. Schedule WorkManager periodic sync if context provided (strictly after DB commit)
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
                    // 1. Tenant/Owner validation
                    val currentUserId = cloudAuthManager.getUserId()
                    if (remoteRecord.owner_id != null && currentUserId != null && remoteRecord.owner_id != currentUserId) {
                        cloudSyncStateDao.recordSyncError("租戶不符：遠端記錄擁有者 (${remoteRecord.owner_id}) 與當前租戶 ($currentUserId) 不一致")
                        batchSuccess = false
                        break
                    }

                    // 2. Structural validation before decryption
                    if (remoteRecord.memory_id.isBlank()) {
                        cloudSyncStateDao.recordSyncError("遠端記錄 memory_id 為空")
                        batchSuccess = false
                        break
                    }
                    if (remoteRecord.revision <= 0) {
                        cloudSyncStateDao.recordSyncError("遠端記錄 revision 必須為正整數：${remoteRecord.revision}")
                        batchSuccess = false
                        break
                    }
                    if (remoteRecord.change_seq <= currentPullSeq) {
                        cloudSyncStateDao.recordSyncError("遠端記錄 change_seq (${remoteRecord.change_seq}) 必須大於當前 cursor ($currentPullSeq)")
                        batchSuccess = false
                        break
                    }
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

                    // Nonce Base64 & 12 bytes check
                    val decodedNonce = try {
                        Base64.getDecoder().decode(remoteRecord.nonce)
                    } catch (e: Exception) {
                        cloudSyncStateDao.recordSyncError("遠端記錄 nonce 非合法 Base64")
                        batchSuccess = false
                        break
                    }
                    if (decodedNonce.size != CloudCryptoService.GCM_NONCE_BYTES) {
                        cloudSyncStateDao.recordSyncError("遠端記錄 nonce 長度不合法 (${decodedNonce.size} bytes，預期 12)")
                        batchSuccess = false
                        break
                    }

                    // Ciphertext Base64 & size bound check
                    val decodedCiphertext = try {
                        Base64.getDecoder().decode(remoteRecord.ciphertext)
                    } catch (e: Exception) {
                        cloudSyncStateDao.recordSyncError("遠端記錄 ciphertext 非合法 Base64")
                        batchSuccess = false
                        break
                    }
                    if (decodedCiphertext.isEmpty()) {
                        cloudSyncStateDao.recordSyncError("遠端記錄密文不可為空")
                        batchSuccess = false
                        break
                    }
                    if (decodedCiphertext.size > CloudCryptoService.MAX_CLOUD_CIPHERTEXT_BYTES) {
                        cloudSyncStateDao.recordSyncError("遠端記錄密文大小超過限制 (${decodedCiphertext.size} bytes)")
                        batchSuccess = false
                        break
                    }

                    // 3. Cryptographic decryption and application
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
