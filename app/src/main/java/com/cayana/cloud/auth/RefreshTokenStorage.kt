package com.cayana.cloud.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RefreshTokenStorage {
    fun saveRefreshToken(token: String)
    fun loadRefreshToken(): String?
    fun clearRefreshToken()
    fun hasRefreshToken(): Boolean
}

class AndroidKeystoreRefreshTokenStorage(
    private val context: Context
) : RefreshTokenStorage {

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "cayana_cloud_refresh_token_wrapper"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "cayana_cloud_secure_prefs"
        private const val PREF_WRAPPED_REFRESH_TOKEN = "wrapped_refresh_token"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveRefreshToken(token: String) {
        val secretKey = getOrCreateWrapperKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        val encoded = Base64.getEncoder().encodeToString(combined)
        prefs.edit().putString(PREF_WRAPPED_REFRESH_TOKEN, encoded).apply()
    }

    override fun loadRefreshToken(): String? {
        val encoded = prefs.getString(PREF_WRAPPED_REFRESH_TOKEN, null) ?: return null
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.size < GCM_IV_LENGTH + 16) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val secretKey = getWrapperKey() ?: return null
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun clearRefreshToken() {
        prefs.edit().remove(PREF_WRAPPED_REFRESH_TOKEN).apply()
    }

    override fun hasRefreshToken(): Boolean {
        return prefs.contains(PREF_WRAPPED_REFRESH_TOKEN)
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

class InMemoryRefreshTokenStorage : RefreshTokenStorage {
    private var storedToken: String? = null

    override fun saveRefreshToken(token: String) {
        storedToken = token
    }

    override fun loadRefreshToken(): String? {
        return storedToken
    }

    override fun clearRefreshToken() {
        storedToken = null
    }

    override fun hasRefreshToken(): Boolean {
        return storedToken != null
    }
}
