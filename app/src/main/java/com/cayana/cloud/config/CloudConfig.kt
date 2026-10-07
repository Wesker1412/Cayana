package com.cayana.cloud.config

object CloudConfig {
    // Default Supabase project URL & Anon Key for Cayana Cloud
    // Only publishable anon key is permitted. Secret/service keys strictly forbidden.
    const val DEFAULT_SUPABASE_URL = "https://cayana-cloud.supabase.co"
    const val DEFAULT_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNheWFuYS1jbG91ZCIsInJvbGUiOiFhbm9uIiwiaWF0IjoxNzI4MjAwMDAwLCJleHAiOjIwNDM3NjAwMDB9.dummy_anon_key_for_cayana_cloud"

    const val MAX_OUTBOX_BATCH_SIZE = 50
    const val MAX_PULL_BATCH_SIZE = 50
    const val MAX_RECORD_CIPHERTEXT_BYTES = 512 * 1024 // 512 KB
}
