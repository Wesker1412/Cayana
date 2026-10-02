package com.cayana.source

import android.content.Context
import android.net.Uri

/**
 * Lightweight validator to check if an external source (e.g. MediaStore screenshot)
 * still exists on the device.
 */
class SourceExistenceValidator(private val context: Context) {

    fun doesSourceExist(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
