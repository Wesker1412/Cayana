package com.cayana.backup.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RecoveryKeyStorage {
    fun saveRecoveryKey(rootKey: ByteArray)
    fun loadRecoveryKey(): ByteArray?
    fun clearRecoveryKey()
    fun hasRecoveryKey(): Boolean
}

class AndroidKeystoreRecoveryKeyStorage(
    private val context: Context
) : RecoveryKeyStorage {

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "cayana_backup_key_wrapper"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "cayana_backup_key_secure_prefs"
        private const val PREF_WRAPPED_KEY = "wrapped_recovery_key"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveRecoveryKey(rootKey: ByteArray) {
        val secretKey = getOrCreateWrapperKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(rootKey)

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        prefs.edit().putString(PREF_WRAPPED_KEY, encoded).apply()
    }

    override fun loadRecoveryKey(): ByteArray? {
        val encoded = prefs.getString(PREF_WRAPPED_KEY, null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH + 16) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val secretKey = getWrapperKey() ?: return null
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    override fun clearRecoveryKey() {
        prefs.edit().remove(PREF_WRAPPED_KEY).apply()
    }

    override fun hasRecoveryKey(): Boolean {
        return prefs.contains(PREF_WRAPPED_KEY)
    }

    private fun getWrapperKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateWrapperKey(): SecretKey {
        val existing = getWrapperKey()
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
}

class InMemoryRecoveryKeyStorage : RecoveryKeyStorage {
    private var storedKey: ByteArray? = null

    override fun saveRecoveryKey(rootKey: ByteArray) {
        storedKey = rootKey.copyOf()
    }

    override fun loadRecoveryKey(): ByteArray? {
        return storedKey?.copyOf()
    }

    override fun clearRecoveryKey() {
        storedKey = null
    }

    override fun hasRecoveryKey(): Boolean {
        return storedKey != null
    }
}
