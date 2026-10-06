package com.cayana.backup.crypto

import com.cayana.backup.config.BackupConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class BackupCryptoTest {

    @Test
    fun recoveryKeyFormatAndParseRoundTrip() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        assertEquals(32, rootKey.size)

        val formatted = RecoveryKeyManager.formatKey(rootKey)
        assertTrue(formatted.contains("-"))

        val parsed = RecoveryKeyManager.parseKey(formatted)
        assertArrayEquals(rootKey, parsed)
    }

    @Test
    fun recoveryKeyParseToleratesSpacesAndHyphensAndCase() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val formatted = RecoveryKeyManager.formatKey(rootKey)

        val messy = "  " + formatted.lowercase().replace("-", " - ") + "  "
        val parsed = RecoveryKeyManager.parseKey(messy)
        assertArrayEquals(rootKey, parsed)
    }

    @Test
    fun recoveryKeyFailsOnCorruptedChecksum() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val formatted = RecoveryKeyManager.formatKey(rootKey)

        // Modify the last character
        val lastChar = formatted.last()
        val replacement = if (lastChar == 'A') 'B' else 'A'
        val corrupted = formatted.dropLast(1) + replacement

        try {
            RecoveryKeyManager.parseKey(corrupted)
            fail("Expected InvalidRecoveryKeyException due to checksum mismatch")
        } catch (e: InvalidRecoveryKeyException) {
            assertTrue(e.message?.contains("檢查碼") == true || e.message?.contains("無效") == true)
        }
    }

    @Test
    fun backupEncryptDecryptRoundTrip() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val plaintext = "Cayana Personal Memory Secret Backup Payload 2026".toByteArray(Charsets.UTF_8)
        val snapshotId = UUID.randomUUID()

        val encryptedBlob = BackupCryptoEngine.encrypt(
            payload = plaintext,
            rootKey = rootKey,
            snapshotId = snapshotId
        )

        val decrypted = BackupCryptoEngine.decrypt(encryptedBlob, rootKey)
        assertEquals(snapshotId, decrypted.snapshotId)
        assertArrayEquals(plaintext, decrypted.payloadBytes)
    }

    @Test
    fun wrongRecoveryKeyFailsAuthentication() {
        val correctKey = RecoveryKeyManager.generateRootKey()
        val wrongKey = RecoveryKeyManager.generateRootKey()
        val plaintext = "Sensitive user memories".toByteArray(Charsets.UTF_8)

        val encryptedBlob = BackupCryptoEngine.encrypt(plaintext, correctKey)

        try {
            BackupCryptoEngine.decrypt(encryptedBlob, wrongKey)
            fail("Expected BackupDecryptionException when using wrong key")
        } catch (e: BackupDecryptionException) {
            assertTrue(e.message?.contains("金鑰不正確") == true)
        }
    }

    @Test
    fun singleBitCiphertextTamperFailsAuthentication() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val plaintext = "Important financial receipt or memo".toByteArray(Charsets.UTF_8)

        val encryptedBlob = BackupCryptoEngine.encrypt(plaintext, rootKey)

        // Flip a single bit in the ciphertext section
        val tamperedBlob = encryptedBlob.copyOf()
        val tamperIndex = tamperedBlob.size - 5
        tamperedBlob[tamperIndex] = (tamperedBlob[tamperIndex].toInt() xor 0x01).toByte()

        try {
            BackupCryptoEngine.decrypt(tamperedBlob, rootKey)
            fail("Expected BackupDecryptionException when ciphertext is tampered")
        } catch (e: BackupDecryptionException) {
            assertTrue(e.message?.contains("金鑰不正確") == true || e.message?.contains("損壞") == true)
        }
    }

    @Test
    fun headerTamperFailsAuthenticationDueToAad() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val plaintext = "Encrypted test message".toByteArray(Charsets.UTF_8)

        val encryptedBlob = BackupCryptoEngine.encrypt(plaintext, rootKey)

        // Tamper with snapshot ID in the header (bytes 6..21)
        val tamperedBlob = encryptedBlob.copyOf()
        tamperedBlob[10] = (tamperedBlob[10].toInt() xor 0xFF).toByte()

        try {
            BackupCryptoEngine.decrypt(tamperedBlob, rootKey)
            fail("Expected BackupDecryptionException when header AAD is tampered")
        } catch (e: BackupDecryptionException) {
            assertTrue(e.message?.contains("金鑰不正確") == true || e.message?.contains("損壞") == true)
        }
    }

    @Test
    fun samePlaintextProducesDifferentCiphertext() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val plaintext = "Identical memory content repeated".toByteArray(Charsets.UTF_8)

        val encryptedBlob1 = BackupCryptoEngine.encrypt(plaintext, rootKey)
        val encryptedBlob2 = BackupCryptoEngine.encrypt(plaintext, rootKey)

        assertFalse(encryptedBlob1.contentEquals(encryptedBlob2))
    }

    @Test
    fun ciphertextDoesNotContainKnownMemoryPlaintext() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val memoryText = "CONFIDENTIAL_PASSWORD_OR_TOKEN_123456789"
        val plaintext = memoryText.toByteArray(Charsets.UTF_8)

        val encryptedBlob = BackupCryptoEngine.encrypt(plaintext, rootKey)
        val rawEncryptedString = String(encryptedBlob, Charsets.ISO_8859_1)

        assertFalse(rawEncryptedString.contains(memoryText))
    }

    @Test
    fun recoveryKeyNeverSerializedIntoBackup() {
        val rootKey = RecoveryKeyManager.generateRootKey()
        val plaintext = "Normal user notes".toByteArray(Charsets.UTF_8)

        val encryptedBlob = BackupCryptoEngine.encrypt(plaintext, rootKey)

        // Check that raw rootKey does not appear anywhere as a contiguous 32-byte subsequence
        fun containsSubsequence(source: ByteArray, target: ByteArray): Boolean {
            if (target.size > source.size) return false
            for (i in 0..(source.size - target.size)) {
                var found = true
                for (j in target.indices) {
                    if (source[i + j] != target[j]) {
                        found = false
                        break
                    }
                }
                if (found) return true
            }
            return false
        }

        assertFalse(containsSubsequence(encryptedBlob, rootKey))
    }
}
