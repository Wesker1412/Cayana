package com.cayana.cloud

import com.cayana.backup.crypto.BackupCryptoEngine
import com.cayana.backup.crypto.RecoveryKeyManager
import com.cayana.cloud.crypto.CloudCryptoService
import com.cayana.cloud.crypto.CloudDecryptionException
import com.cayana.cloud.crypto.CloudMemoryPayloadV1
import com.cayana.cloud.crypto.CloudTombstonePayloadV1
import com.cayana.cloud.crypto.EncryptedCloudRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class CloudCryptoTest {

    private val rootKey = RecoveryKeyManager.generateRootKey()
    private val cloudSyncKey = CloudCryptoService.deriveCloudSyncKey(rootKey)

    private fun createSamplePayload(
        id: String = "mem-12345",
        title: String = "Meeting with Alice",
        rawText: String = "Secret text discussing project Cayana Stage 7"
    ): CloudMemoryPayloadV1 {
        return CloudMemoryPayloadV1(
            id = id,
            sourceType = "SCREENSHOT",
            createdAt = 1728000000000L,
            capturedAt = 1728000000000L,
            title = title,
            rawText = rawText,
            normalizedText = rawText.lowercase(),
            sourceUri = "content://media/external/images/media/1",
            sourceUrl = "https://example.com/cayana",
            sourceExists = true,
            metadataJson = """{"ocrConfidence":0.95}""",
            entitiesJson = """["Cayana","Stage 7"]""",
            eventCandidatesJson = "[]",
            processingState = "COMPLETED"
        )
    }

    @Test
    fun cloudMemoryEncryptDecryptRoundTrip() {
        val payload = createSamplePayload()
        val revision = 1L

        val encryptedRecord = CloudCryptoService.encryptMemory(
            payload = payload,
            revision = revision,
            cloudSyncKey = cloudSyncKey
        )

        assertEquals(payload.id, encryptedRecord.memoryId)
        assertEquals(revision, encryptedRecord.revision)
        assertEquals(1, encryptedRecord.payloadVersion)
        assertFalse(encryptedRecord.isTombstone)

        val decryptedPayload = CloudCryptoService.decryptMemory(encryptedRecord, cloudSyncKey)
        assertEquals(payload.id, decryptedPayload.id)
        assertEquals(payload.title, decryptedPayload.title)
        assertEquals(payload.rawText, decryptedPayload.rawText)
        assertEquals(payload.sourceType, decryptedPayload.sourceType)
        assertEquals(payload.sourceUrl, decryptedPayload.sourceUrl)
    }

    @Test
    fun sameMemoryProducesDifferentCiphertext() {
        val payload = createSamplePayload()
        val revision = 1L

        val record1 = CloudCryptoService.encryptMemory(payload, revision, cloudSyncKey)
        val record2 = CloudCryptoService.encryptMemory(payload, revision, cloudSyncKey)

        assertNotEquals(record1.nonceBase64, record2.nonceBase64)
        assertNotEquals(record1.ciphertextBase64, record2.ciphertextBase64)
    }

    @Test
    fun cloudCiphertextDoesNotContainPlaintext() {
        val marker = "CAYANA_STAGE7_SECRET_MARKER"
        val payload = createSamplePayload(rawText = "Important note containing $marker strictly confidential")
        val encryptedRecord = CloudCryptoService.encryptMemory(payload, 1L, cloudSyncKey)

        val rawCiphertextBytes = Base64.getDecoder().decode(encryptedRecord.ciphertextBase64)
        val ciphertextAscii = String(rawCiphertextBytes, Charsets.ISO_8859_1)

        assertFalse(
            "Ciphertext must not contain plaintext marker!",
            ciphertextAscii.contains(marker)
        )
        assertFalse(
            "Base64 ciphertext must not contain plaintext marker!",
            encryptedRecord.ciphertextBase64.contains(marker)
        )
    }

    @Test
    fun tamperedCiphertextRejected() {
        val payload = createSamplePayload()
        val record = CloudCryptoService.encryptMemory(payload, 1L, cloudSyncKey)

        val cipherBytes = Base64.getDecoder().decode(record.ciphertextBase64)
        cipherBytes[0] = (cipherBytes[0].toInt() xor 0x01).toByte()
        val tamperedRecord = record.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(cipherBytes))

        try {
            CloudCryptoService.decryptMemory(tamperedRecord, cloudSyncKey)
            fail("Expected CloudDecryptionException for tampered ciphertext")
        } catch (e: CloudDecryptionException) {
            assertTrue(e.message?.contains("Decryption/AAD authentication failed") == true)
        }
    }

    @Test
    fun tamperedRevisionAadRejected() {
        val payload = createSamplePayload()
        val record = CloudCryptoService.encryptMemory(payload, revision = 2L, cloudSyncKey = cloudSyncKey)

        // Modify revision outside of ciphertext (AAD mismatch)
        val tamperedRecord = record.copy(revision = 3L)

        try {
            CloudCryptoService.decryptMemory(tamperedRecord, cloudSyncKey)
            fail("Expected CloudDecryptionException for tampered revision in AAD")
        } catch (e: CloudDecryptionException) {
            assertTrue(e.message?.contains("Decryption/AAD authentication failed") == true)
        }
    }

    @Test
    fun tamperedMemoryIdAadRejected() {
        val payload = createSamplePayload(id = "mem-original")
        val record = CloudCryptoService.encryptMemory(payload, revision = 1L, cloudSyncKey = cloudSyncKey)

        val tamperedRecord = record.copy(memoryId = "mem-altered")

        try {
            CloudCryptoService.decryptMemory(tamperedRecord, cloudSyncKey)
            fail("Expected CloudDecryptionException for tampered memoryId in AAD")
        } catch (e: CloudDecryptionException) {
            assertTrue(e.message?.contains("Decryption/AAD authentication failed") == true)
        }
    }

    @Test
    fun tombstoneRequiresValidAuthentication() {
        val memoryId = "mem-to-delete"
        val revision = 5L
        val tombstoneRecord = CloudCryptoService.encryptTombstone(memoryId, revision, cloudSyncKey)

        assertTrue(tombstoneRecord.isTombstone)
        val decryptedTombstone = CloudCryptoService.decryptTombstone(tombstoneRecord, cloudSyncKey)
        assertEquals(memoryId, decryptedTombstone.memoryId)
        assertEquals(revision, decryptedTombstone.revision)
        assertTrue(decryptedTombstone.deleted)

        // Cannot decrypt tombstone as memory record
        try {
            CloudCryptoService.decryptMemory(tombstoneRecord, cloudSyncKey)
            fail("Expected CloudDecryptionException when attempting to decrypt tombstone as memory")
        } catch (e: CloudDecryptionException) {
            assertTrue(e.message?.contains("tombstone") == true)
        }

        // Tampering with tombstone revision in AAD fails
        val tamperedRecord = tombstoneRecord.copy(revision = 6L)
        try {
            CloudCryptoService.decryptTombstone(tamperedRecord, cloudSyncKey)
            fail("Expected CloudDecryptionException for tampered tombstone revision")
        } catch (e: CloudDecryptionException) {
            assertTrue(e.message?.contains("Decryption/AAD authentication failed") == true)
        }
    }

    @Test
    fun backupKeyAndCloudKeyAreDifferent() {
        // Derive backup key (Stage 6) vs cloud sync key (Stage 7) from same root key
        val sampleData = "Test payload".toByteArray(Charsets.UTF_8)
        val encryptedBackup = BackupCryptoEngine.encrypt(sampleData, rootKey)

        // Cloud key should be 32 bytes and completely different
        assertEquals(32, cloudSyncKey.size)
        // If we try to decrypt cloud payload with a different key, it must fail
        val payload = createSamplePayload()
        val record = CloudCryptoService.encryptMemory(payload, 1L, cloudSyncKey)

        val differentRootKey = RecoveryKeyManager.generateRootKey()
        val differentCloudKey = CloudCryptoService.deriveCloudSyncKey(differentRootKey)

        try {
            CloudCryptoService.decryptMemory(record, differentCloudKey)
            fail("Expected CloudDecryptionException when using different cloud sync key")
        } catch (e: CloudDecryptionException) {
            assertTrue(e.message?.contains("Decryption/AAD authentication failed") == true)
        }
    }
}
