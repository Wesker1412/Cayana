package com.cayana.search.data

import android.content.Context

interface SearchIndexVersionStorage {
    suspend fun getIndexFormatVersion(): Int
    suspend fun setIndexFormatVersion(version: Int)
}

class SharedPrefsSearchIndexVersionStorage(
    context: Context
) : SearchIndexVersionStorage {
    private val prefs = context.getSharedPreferences("cayana_search_index_prefs", Context.MODE_PRIVATE)

    override suspend fun getIndexFormatVersion(): Int {
        return prefs.getInt("search_index_format_version", 1)
    }

    override suspend fun setIndexFormatVersion(version: Int) {
        prefs.edit().putInt("search_index_format_version", version).apply()
    }
}

class InMemorySearchIndexVersionStorage(
    initialVersion: Int = 1
) : SearchIndexVersionStorage {
    private var version: Int = initialVersion

    override suspend fun getIndexFormatVersion(): Int = version

    override suspend fun setIndexFormatVersion(version: Int) {
        this.version = version
    }
}
