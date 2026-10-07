package com.cayana.cloud.client

import com.cayana.cloud.auth.CloudAuthManager
import com.cayana.cloud.crypto.EncryptedCloudRecord
import com.cayana.core.common.Result
import com.cayana.core.logging.CayanaLogger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class CloudRemoteRecord(
    val owner_id: String? = null,
    val memory_id: String,
    val revision: Long,
    val payload_version: Int,
    val nonce: String,
    val ciphertext: String,
    val is_tombstone: Boolean,
    val change_seq: Long,
    val updated_at: String? = null
)

@Serializable
data class CloudUpsertResponse(
    val status: String, // "ACCEPTED", "IDEMPOTENT", "STALE"
    val revision: Long? = null,
    val change_seq: Long? = null
)

interface CayanaCloudClient {
    suspend fun upsertRecord(record: EncryptedCloudRecord): Result<CloudUpsertResponse>
    suspend fun pullRecords(afterSeq: Long, limit: Int): Result<List<CloudRemoteRecord>>
}

class SupabaseCayanaCloudClient(
    private val supabaseClient: SupabaseClient,
    private val authManager: CloudAuthManager
) : CayanaCloudClient {

    override suspend fun upsertRecord(record: EncryptedCloudRecord): Result<CloudUpsertResponse> = withContext(Dispatchers.IO) {
        val tokenResult = authManager.getValidAccessToken()
        if (tokenResult !is Result.Success) {
            return@withContext Result.Error(IllegalStateException("Not authenticated with Cayana Cloud"))
        }

        try {
            val rpcParams = buildJsonObject {
                put("p_memory_id", record.memoryId)
                put("p_revision", record.revision)
                put("p_payload_version", record.payloadVersion)
                put("p_nonce", record.nonceBase64)
                put("p_ciphertext", record.ciphertextBase64)
                put("p_is_tombstone", record.isTombstone)
            }

            val response = supabaseClient.postgrest.rpc(
                function = "upsert_cloud_memory_record",
                parameters = rpcParams
            ).decodeAs<JsonObject>()

            val status = response["status"]?.jsonPrimitive?.content ?: "ACCEPTED"
            val revision = response["revision"]?.jsonPrimitive?.longOrNull
            val changeSeq = response["change_seq"]?.jsonPrimitive?.longOrNull

            Result.Success(
                CloudUpsertResponse(
                    status = status,
                    revision = revision,
                    change_seq = changeSeq
                )
            )
        } catch (e: Exception) {
            CayanaLogger.w("CloudClient", "Failed to upsert cloud memory record via RPC", e)
            Result.Error(e)
        }
    }

    override suspend fun pullRecords(afterSeq: Long, limit: Int): Result<List<CloudRemoteRecord>> = withContext(Dispatchers.IO) {
        val tokenResult = authManager.getValidAccessToken()
        if (tokenResult !is Result.Success) {
            return@withContext Result.Error(IllegalStateException("Not authenticated with Cayana Cloud"))
        }

        try {
            val records = supabaseClient.from("cloud_memory_records")
                .select {
                    filter {
                        gt("change_seq", afterSeq)
                    }
                    order(column = "change_seq", order = Order.ASCENDING)
                    limit(limit.toLong())
                }
                .decodeList<CloudRemoteRecord>()

            Result.Success(records)
        } catch (e: Exception) {
            CayanaLogger.w("CloudClient", "Failed to pull cloud memory records", e)
            Result.Error(e)
        }
    }
}
