package com.cayana.cloud.config

import com.cayana.BuildConfig

object CloudConfig {
    // Read from BuildConfig / environment configuration
    // Confidential backend admin tokens are strictly disallowed in client code.
    val supabaseUrl: String = BuildConfig.CAYANA_SUPABASE_URL
    val supabasePublishableKey: String = BuildConfig.CAYANA_SUPABASE_PUBLISHABLE_KEY

    fun isConfigured(): Boolean {
        return supabaseUrl.isNotBlank() && supabasePublishableKey.isNotBlank()
    }

    const val MAX_OUTBOX_BATCH_SIZE = 50
    const val MAX_PULL_BATCH_SIZE = 50
    const val MAX_CLOUD_CIPHERTEXT_BYTES = 524288 // Exactly 512 KiB (524,288 bytes)
    const val MAX_RECORD_CIPHERTEXT_BYTES = MAX_CLOUD_CIPHERTEXT_BYTES
}
