package com.cayana.actions.notion.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface NotionTokenStorage {
    fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        workspaceId: String? = null,
        workspaceName: String? = null,
        workspaceIcon: String? = null,
        botId: String? = null
    )
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun getWorkspaceId(): String?
    fun getWorkspaceName(): String?
    fun getWorkspaceIcon(): String?
    fun getBotId(): String?
    fun clearTokens()
    fun hasTokens(): Boolean
}

class AndroidKeystoreNotionTokenStorage(
    private val context: Context
) : NotionTokenStorage {

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "cayana_notion_token_wrapper"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "cayana_notion_secure_prefs"
        private const val PREF_WRAPPED_ACCESS_TOKEN = "wrapped_access_token"
        private const val PREF_WRAPPED_REFRESH_TOKEN = "wrapped_refresh_token"
        private const val PREF_WORKSPACE_ID = "workspace_id"
        private const val PREF_WORKSPACE_NAME = "workspace_name"
        private const val PREF_WORKSPACE_ICON = "workspace_icon"
        private const val PREF_BOT_ID = "bot_id"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedRefreshToken: String? = null

    override fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        workspaceId: String?,
        workspaceName: String?,
        workspaceIcon: String?,
        botId: String?
    ) {
        val editor = prefs.edit()

        val encryptedAccess = encryptString(accessToken)
        if (encryptedAccess != null) {
            editor.putString(PREF_WRAPPED_ACCESS_TOKEN, encryptedAccess)
            cachedAccessToken = accessToken
        }

        if (!refreshToken.isNullOrBlank()) {
            val encryptedRefresh = encryptString(refreshToken)
            if (encryptedRefresh != null) {
                editor.putString(PREF_WRAPPED_REFRESH_TOKEN, encryptedRefresh)
                cachedRefreshToken = refreshToken
            }
        } else {
            editor.remove(PREF_WRAPPED_REFRESH_TOKEN)
            cachedRefreshToken = null
        }

        if (workspaceId != null) editor.putString(PREF_WORKSPACE_ID, workspaceId) else editor.remove(PREF_WORKSPACE_ID)
        if (workspaceName != null) editor.putString(PREF_WORKSPACE_NAME, workspaceName) else editor.remove(PREF_WORKSPACE_NAME)
        if (workspaceIcon != null) editor.putString(PREF_WORKSPACE_ICON, workspaceIcon) else editor.remove(PREF_WORKSPACE_ICON)
        if (botId != null) editor.putString(PREF_BOT_ID, botId) else editor.remove(PREF_BOT_ID)

        editor.apply()
    }

    override fun getAccessToken(): String? {
        val cached = cachedAccessToken
        if (!cached.isNullOrBlank()) return cached

        val encrypted = prefs.getString(PREF_WRAPPED_ACCESS_TOKEN, null) ?: return null
        val decrypted = decryptString(encrypted)
        if (!decrypted.isNullOrBlank()) {
            cachedAccessToken = decrypted
        }
        return decrypted
    }

    override fun getRefreshToken(): String? {
        val cached = cachedRefreshToken
        if (!cached.isNullOrBlank()) return cached

        val encrypted = prefs.getString(PREF_WRAPPED_REFRESH_TOKEN, null) ?: return null
        val decrypted = decryptString(encrypted)
        if (!decrypted.isNullOrBlank()) {
            cachedRefreshToken = decrypted
        }
        return decrypted
    }

    override fun getWorkspaceId(): String? = prefs.getString(PREF_WORKSPACE_ID, null)
    override fun getWorkspaceName(): String? = prefs.getString(PREF_WORKSPACE_NAME, null)
    override fun getWorkspaceIcon(): String? = prefs.getString(PREF_WORKSPACE_ICON, null)
    override fun getBotId(): String? = prefs.getString(PREF_BOT_ID, null)

    override fun clearTokens() {
        cachedAccessToken = null
        cachedRefreshToken = null
        prefs.edit().clear().apply()
    }

    override fun hasTokens(): Boolean {
        return !cachedAccessToken.isNullOrBlank() || prefs.contains(PREF_WRAPPED_ACCESS_TOKEN)
    }

    private fun encryptString(plaintext: String): String? {
        return try {
            val secretKey = getOrCreateWrapperKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptString(encodedCiphertext: String): String? {
        return try {
            val combined = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
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

    private fun getWrapperKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateWrapperKey(): SecretKey {
        getWrapperKey()?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
