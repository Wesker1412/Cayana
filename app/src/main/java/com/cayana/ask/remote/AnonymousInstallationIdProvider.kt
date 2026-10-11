package com.cayana.ask.remote

import android.content.Context
import java.util.UUID

/**
 * Provides an anonymous installation credential for basic abuse prevention,
 * rate limiting, and quota enforcement on the Cayana LLM API server.
 *
 * Strict Privacy Guarantees:
 * 1. Zero linkage to email, phone, user names, or device serial numbers.
 * 2. Uncorrelated with personal memories or database records.
 * 3. Pure opaque client-generated UUID.
 */
interface AnonymousInstallationIdProvider {
    fun getInstallationId(): String
}

class SharedPreferencesInstallationIdProvider(
    private val context: Context
) : AnonymousInstallationIdProvider {

    @Volatile
    private var cachedId: String? = null

    override fun getInstallationId(): String {
        cachedId?.let { return it }

        synchronized(this) {
            cachedId?.let { return it }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_INSTALLATION_ID, null)

            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_INSTALLATION_ID, id).apply()
            }

            cachedId = id
            return id
        }
    }

    companion object {
        private const val PREFS_NAME = "cayana_anonymous_credentials"
        private const val KEY_INSTALLATION_ID = "anonymous_installation_id"
    }
}

/**
 * In-memory provider for testing and decoupled environments.
 */
class InMemoryInstallationIdProvider(
    private val installationId: String = UUID.randomUUID().toString()
) : AnonymousInstallationIdProvider {
    override fun getInstallationId(): String = installationId
}
