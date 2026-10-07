package com.cayana.cloud.client

import com.cayana.cloud.crypto.EncryptedCloudRecord
import com.cayana.core.common.Result
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class FakeCayanaCloudClient : CayanaCloudClient {

    private val serverSequence = AtomicLong(1L)
    // Map of memoryId -> CloudRemoteRecord
    val remoteRecords = ConcurrentHashMap<String, CloudRemoteRecord>()

    var shouldFailUpsert: Boolean = false
    var shouldFailPull: Boolean = false
    var onUpsertListener: ((EncryptedCloudRecord) -> Unit)? = null

    override suspend fun upsertRecord(record: EncryptedCloudRecord): Result<CloudUpsertResponse> {
        if (shouldFailUpsert) {
            return Result.Error(IllegalStateException("Simulated cloud upsert failure"))
        }

        onUpsertListener?.invoke(record)

        val existing = remoteRecords[record.memoryId]
        if (existing == null) {
            val newSeq = serverSequence.incrementAndGet()
            val remoteRecord = CloudRemoteRecord(
                owner_id = "tenant-current",
                memory_id = record.memoryId,
                revision = record.revision,
                payload_version = record.payloadVersion,
                nonce = record.nonceBase64,
                ciphertext = record.ciphertextBase64,
                is_tombstone = record.isTombstone,
                change_seq = newSeq
            )
            remoteRecords[record.memoryId] = remoteRecord
            return Result.Success(CloudUpsertResponse(status = "ACCEPTED", revision = record.revision, change_seq = newSeq))
        } else if (record.revision > existing.revision) {
            val newSeq = serverSequence.incrementAndGet()
            val remoteRecord = CloudRemoteRecord(
                owner_id = "tenant-current",
                memory_id = record.memoryId,
                revision = record.revision,
                payload_version = record.payloadVersion,
                nonce = record.nonceBase64,
                ciphertext = record.ciphertextBase64,
                is_tombstone = record.isTombstone,
                change_seq = newSeq
            )
            remoteRecords[record.memoryId] = remoteRecord
            return Result.Success(CloudUpsertResponse(status = "ACCEPTED", revision = record.revision, change_seq = newSeq))
        } else if (record.revision == existing.revision) {
            // Idempotent retry
            return Result.Success(CloudUpsertResponse(status = "IDEMPOTENT", revision = record.revision, change_seq = existing.change_seq))
        } else {
            // Stale
            return Result.Success(CloudUpsertResponse(status = "STALE", revision = existing.revision))
        }
    }

    override suspend fun pullRecords(afterSeq: Long, limit: Int): Result<List<CloudRemoteRecord>> {
        if (shouldFailPull) {
            return Result.Error(IllegalStateException("Simulated cloud pull failure"))
        }

        val filtered = remoteRecords.values
            .filter { it.change_seq > afterSeq }
            .sortedBy { it.change_seq }
            .take(limit)

        return Result.Success(filtered)
    }

    fun clearRemote() {
        remoteRecords.clear()
        serverSequence.set(1L)
    }
}
