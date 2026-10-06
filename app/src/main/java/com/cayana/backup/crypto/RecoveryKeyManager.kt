package com.cayana.backup.crypto

import com.cayana.backup.config.BackupConfig
import java.security.MessageDigest
import java.security.SecureRandom

class InvalidRecoveryKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

object RecoveryKeyManager {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val CHECKSUM_BYTES_LENGTH = 2
    private const val CHUNK_SIZE = 5

    /**
     * Generates a cryptographically secure 256-bit (32 bytes) root recovery key.
     */
    fun generateRootKey(): ByteArray {
        val key = ByteArray(BackupConfig.ROOT_KEY_BYTES_LENGTH)
        SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Computes a 2-byte checksum of the given key using SHA-256.
     */
    fun computeChecksum(key: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return byteArrayOf(digest[0], digest[1])
    }

    /**
     * Encodes a 256-bit root key into a user-friendly, chunked Base32 string with checksum.
     * E.g. "XXXXX-XXXXX-XXXXX-..."
     */
    fun formatKey(rootKey: ByteArray): String {
        require(rootKey.size == BackupConfig.ROOT_KEY_BYTES_LENGTH) {
            "Root key must be exactly ${BackupConfig.ROOT_KEY_BYTES_LENGTH} bytes"
        }
        val checksum = computeChecksum(rootKey)
        val combined = rootKey + checksum
        val base32 = encodeBase32(combined)
        return base32.chunked(CHUNK_SIZE).joinToString("-")
    }

    /**
     * Parses and validates a user-entered recovery key string.
     * Verifies length, character set, and checksum.
     * Throws [InvalidRecoveryKeyException] on any validation failure.
     */
    @Throws(InvalidRecoveryKeyException::class)
    fun parseKey(formattedKey: String): ByteArray {
        val cleaned = formattedKey.trim().replace("-", "").replace(" ", "").uppercase()
        if (cleaned.isEmpty()) {
            throw InvalidRecoveryKeyException("復原金鑰不得為空。")
        }

        for (c in cleaned) {
            if (c !in ALPHABET) {
                throw InvalidRecoveryKeyException("復原金鑰包含無效字元：$c")
            }
        }

        val decoded = try {
            decodeBase32(cleaned)
        } catch (e: InvalidRecoveryKeyException) {
            throw e
        } catch (e: Exception) {
            throw InvalidRecoveryKeyException("無法解析復原金鑰編碼：${e.message}", e)
        }

        val expectedTotalLength = BackupConfig.ROOT_KEY_BYTES_LENGTH + CHECKSUM_BYTES_LENGTH
        if (decoded.size < expectedTotalLength) {
            throw InvalidRecoveryKeyException("復原金鑰長度不足。")
        }

        val keyBytes = decoded.copyOfRange(0, BackupConfig.ROOT_KEY_BYTES_LENGTH)
        val expectedChecksum = computeChecksum(keyBytes)
        val actualChecksum = decoded.copyOfRange(BackupConfig.ROOT_KEY_BYTES_LENGTH, expectedTotalLength)

        if (!actualChecksum.contentEquals(expectedChecksum)) {
            throw InvalidRecoveryKeyException("復原金鑰檢查碼不符，請確認是否輸入正確。")
        }

        return keyBytes
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(ALPHABET[index])
        }
        return sb.toString()
    }

    private fun decodeBase32(cleanString: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in cleanString) {
            val index = ALPHABET.indexOf(c)
            if (index == -1) throw InvalidRecoveryKeyException("復原金鑰包含無效字元：$c")
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                val b = (buffer shr (bitsLeft - 8)) and 0xFF
                out.write(b)
                bitsLeft -= 8
            }
        }
        if (bitsLeft > 0 && (buffer and ((1 shl bitsLeft) - 1)) != 0) {
            throw InvalidRecoveryKeyException("復原金鑰包含無效的填補位元。")
        }
        return out.toByteArray()
    }
}
