package com.cayana.backup.crypto

import com.cayana.backup.config.BackupConfig
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DecryptedBackupPayload(
    val snapshotId: UUID,
    val payloadBytes: ByteArray
)

object BackupCryptoEngine {

    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SPEC_ALGORITHM = "AES"
    private const val HEADER_SIZE = 4 + 2 + 16 + BackupConfig.SALT_BYTES_LENGTH + BackupConfig.NONCE_BYTES_LENGTH // 50 bytes

    /**
     * Encrypts the raw archive bytes into the Cayana Backup Format v1 envelope.
     */
    fun encrypt(
        payload: ByteArray,
        rootKey: ByteArray,
        snapshotId: UUID = UUID.randomUUID()
    ): ByteArray {
        require(rootKey.size == BackupConfig.ROOT_KEY_BYTES_LENGTH) {
            "Root key must be ${BackupConfig.ROOT_KEY_BYTES_LENGTH} bytes"
        }

        val random = SecureRandom()
        val salt = ByteArray(BackupConfig.SALT_BYTES_LENGTH).also { random.nextBytes(it) }
        val nonce = ByteArray(BackupConfig.NONCE_BYTES_LENGTH).also { random.nextBytes(it) }

        // Build 50-byte header: Magic(4) + Version(2) + SnapshotId(16) + Salt(16) + Nonce(12)
        val header = ByteBuffer.allocate(HEADER_SIZE).apply {
            put(BackupConfig.MAGIC)
            putShort(BackupConfig.BACKUP_FORMAT_VERSION)
            putLong(snapshotId.mostSignificantBits)
            putLong(snapshotId.leastSignificantBits)
            put(salt)
            put(nonce)
        }.array()

        // Derive 256-bit encryption key via HKDF-SHA256
        val derivedKey = HkdfSha256.deriveKey(
            ikm = rootKey,
            salt = salt,
            info = BackupConfig.HKDF_INFO,
            length = 32
        )

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val keySpec = SecretKeySpec(derivedKey, KEY_SPEC_ALGORITHM)
        val gcmSpec = GCMParameterSpec(BackupConfig.GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(header)

        val ciphertext = cipher.doFinal(payload)

        val result = ByteBuffer.allocate(header.size + ciphertext.size).apply {
            put(header)
            put(ciphertext)
        }.array()

        return result
    }

    /**
     * Decrypts an encrypted Cayana Backup Format v1 blob.
     * Authenticates the envelope header via AAD and decrypts ciphertext via AES-256-GCM.
     *
     * @throws BackupDecryptionException on wrong key, corrupted bits, or invalid header.
     */
    @Throws(BackupDecryptionException::class)
    fun decrypt(
        encryptedBlob: ByteArray,
        rootKey: ByteArray
    ): DecryptedBackupPayload {
        if (rootKey.size != BackupConfig.ROOT_KEY_BYTES_LENGTH) {
            throw BackupDecryptionException("復原金鑰不正確，或備份已損壞。")
        }

        val minAllowedSize = HEADER_SIZE + (BackupConfig.GCM_TAG_LENGTH_BITS / 8) // 50 + 16 = 66
        if (encryptedBlob.size < minAllowedSize) {
            throw BackupDecryptionException("備份檔案過小或格式不完整。")
        }

        val buffer = ByteBuffer.wrap(encryptedBlob)

        // 1. Verify Magic
        val magic = ByteArray(BackupConfig.MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(BackupConfig.MAGIC)) {
            throw BackupDecryptionException("無效的備份格式標頭。")
        }

        // 2. Verify Version
        val version = buffer.short
        if (version != BackupConfig.BACKUP_FORMAT_VERSION) {
            throw BackupDecryptionException("不支援的備份版本：$version（當前支援版本：${BackupConfig.BACKUP_FORMAT_VERSION}）")
        }

        // 3. Extract Snapshot UUID
        val mostSig = buffer.long
        val leastSig = buffer.long
        val snapshotId = UUID(mostSig, leastSig)

        // 4. Extract Salt & Nonce
        val salt = ByteArray(BackupConfig.SALT_BYTES_LENGTH)
        buffer.get(salt)
        val nonce = ByteArray(BackupConfig.NONCE_BYTES_LENGTH)
        buffer.get(nonce)

        val header = encryptedBlob.copyOfRange(0, HEADER_SIZE)
        val ciphertext = encryptedBlob.copyOfRange(HEADER_SIZE, encryptedBlob.size)

        // 5. Derive encryption key
        val derivedKey = HkdfSha256.deriveKey(
            ikm = rootKey,
            salt = salt,
            info = BackupConfig.HKDF_INFO,
            length = 32
        )

        return try {
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val keySpec = SecretKeySpec(derivedKey, KEY_SPEC_ALGORITHM)
            val gcmSpec = GCMParameterSpec(BackupConfig.GCM_TAG_LENGTH_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.updateAAD(header)

            val decrypted = cipher.doFinal(ciphertext)
            DecryptedBackupPayload(snapshotId = snapshotId, payloadBytes = decrypted)
        } catch (e: AEADBadTagException) {
            throw BackupDecryptionException("復原金鑰不正確，或備份已損壞。", e)
        } catch (e: GeneralSecurityException) {
            throw BackupDecryptionException("備份解密驗證失敗：${e.message}", e)
        }
    }
}
