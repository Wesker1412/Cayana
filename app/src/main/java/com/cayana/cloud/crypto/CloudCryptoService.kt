package com.cayana.cloud.crypto

import com.cayana.backup.crypto.HkdfSha256
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CloudDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

object CloudCryptoService {

    const val PAYLOAD_VERSION_V1 = 1
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128
    const val MAX_CIPHERTEXT_BYTES = 512 * 1024 // 512 KB

    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val DOMAIN_CLOUD_SYNC = "CayanaCloudSyncKeyV1"

    private val secureRandom = SecureRandom()

    fun deriveCloudSyncKey(recoveryRootKey: ByteArray): ByteArray {
        require(recoveryRootKey.size == 32) { "Recovery root key must be 32 bytes" }
        return HkdfSha256.deriveKey(
            ikm = recoveryRootKey,
            salt = ByteArray(32),
            info = DOMAIN_CLOUD_SYNC.toByteArray(Charsets.UTF_8),
            length = 32
        )
    }

    fun buildAad(
        memoryId: String,
        revision: Long,
        payloadVersion: Int,
        isTombstone: Boolean
    ): ByteArray {
        val aadString = "CAYANA_CLOUD_V1|$memoryId|$revision|$payloadVersion|$isTombstone"
        return aadString.toByteArray(Charsets.UTF_8)
    }

    fun encryptMemory(
        payload: CloudMemoryPayloadV1,
        revision: Long,
        cloudSyncKey: ByteArray
    ): EncryptedCloudRecord {
        return encryptInternal(
            memoryId = payload.id,
            revision = revision,
            payloadVersion = PAYLOAD_VERSION_V1,
            isTombstone = false,
            plaintextJson = payload.toJson(),
            cloudSyncKey = cloudSyncKey
        )
    }

    fun encryptTombstone(
        memoryId: String,
        revision: Long,
        cloudSyncKey: ByteArray
    ): EncryptedCloudRecord {
        val tombstone = CloudTombstonePayloadV1(memoryId = memoryId, revision = revision, deleted = true)
        return encryptInternal(
            memoryId = memoryId,
            revision = revision,
            payloadVersion = PAYLOAD_VERSION_V1,
            isTombstone = true,
            plaintextJson = tombstone.toJson(),
            cloudSyncKey = cloudSyncKey
        )
    }

    private fun encryptInternal(
        memoryId: String,
        revision: Long,
        payloadVersion: Int,
        isTombstone: Boolean,
        plaintextJson: String,
        cloudSyncKey: ByteArray
    ): EncryptedCloudRecord {
        val nonce = ByteArray(GCM_NONCE_BYTES)
        secureRandom.nextBytes(nonce)

        val aad = buildAad(memoryId, revision, payloadVersion, isTombstone)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(cloudSyncKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)

        val ciphertext = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            throw IllegalArgumentException("Ciphertext size ${ciphertext.size} exceeds maximum limit of $MAX_CIPHERTEXT_BYTES bytes")
        }

        return EncryptedCloudRecord(
            memoryId = memoryId,
            revision = revision,
            payloadVersion = payloadVersion,
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            isTombstone = isTombstone
        )
    }

    @Throws(CloudDecryptionException::class)
    fun decryptMemory(
        record: EncryptedCloudRecord,
        cloudSyncKey: ByteArray
    ): CloudMemoryPayloadV1 {
        if (record.isTombstone) {
            throw CloudDecryptionException("Expected memory record but found tombstone")
        }
        val plaintextJson = decryptRaw(record, cloudSyncKey)
        val payload = try {
            CloudMemoryPayloadV1.fromJson(plaintextJson)
        } catch (e: Exception) {
            throw CloudDecryptionException("Failed to parse decrypted memory JSON", e)
        }
        if (payload.id != record.memoryId) {
            throw CloudDecryptionException("Inner memory ID '${payload.id}' does not match record memory ID '${record.memoryId}'")
        }
        return payload
    }

    @Throws(CloudDecryptionException::class)
    fun decryptTombstone(
        record: EncryptedCloudRecord,
        cloudSyncKey: ByteArray
    ): CloudTombstonePayloadV1 {
        if (!record.isTombstone) {
            throw CloudDecryptionException("Expected tombstone record but found memory")
        }
        val plaintextJson = decryptRaw(record, cloudSyncKey)
        val tombstone = try {
            CloudTombstonePayloadV1.fromJson(plaintextJson)
        } catch (e: Exception) {
            throw CloudDecryptionException("Failed to parse decrypted tombstone JSON", e)
        }
        if (tombstone.memoryId != record.memoryId) {
            throw CloudDecryptionException("Inner tombstone memory ID '${tombstone.memoryId}' does not match record memory ID '${record.memoryId}'")
        }
        if (tombstone.revision != record.revision) {
            throw CloudDecryptionException("Inner tombstone revision ${tombstone.revision} does not match record revision ${record.revision}")
        }
        if (!tombstone.deleted) {
            throw CloudDecryptionException("Inner tombstone deleted flag must be true")
        }
        return tombstone
    }

    @Throws(CloudDecryptionException::class)
    private fun decryptRaw(
        record: EncryptedCloudRecord,
        cloudSyncKey: ByteArray
    ): String {
        val nonce = try {
            Base64.getDecoder().decode(record.nonceBase64)
        } catch (e: Exception) {
            throw CloudDecryptionException("Invalid Base64 nonce", e)
        }
        if (nonce.size != GCM_NONCE_BYTES) {
            throw CloudDecryptionException("Invalid nonce length: ${nonce.size} (expected $GCM_NONCE_BYTES)")
        }

        val ciphertext = try {
            Base64.getDecoder().decode(record.ciphertextBase64)
        } catch (e: Exception) {
            throw CloudDecryptionException("Invalid Base64 ciphertext", e)
        }
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            throw CloudDecryptionException("Ciphertext size ${ciphertext.size} exceeds maximum limit of $MAX_CIPHERTEXT_BYTES bytes")
        }

        val aad = buildAad(record.memoryId, record.revision, record.payloadVersion, record.isTombstone)

        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val keySpec = SecretKeySpec(cloudSyncKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.updateAAD(aad)
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw CloudDecryptionException("Decryption/AAD authentication failed: ${e.message}", e)
        }
    }
}
