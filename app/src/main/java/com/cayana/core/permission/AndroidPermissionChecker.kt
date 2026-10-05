package com.cayana.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.cayana.source.SourceType

/**
 * Android implementation of PermissionChecker adhering to Android version-specific
 * permission models (API 34+ Selected Media Access, API 33 Granular Media Permissions,
 * API <=32 External Storage, Media Audio for Recordings, SAF for Downloads).
 */
class AndroidPermissionChecker(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : PermissionChecker {

    override fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun getRequiredPermissions(sourceType: SourceType): List<String> {
        return when (sourceType) {
            SourceType.SCREENSHOT, SourceType.PHOTO -> {
                when {
                    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        listOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    }
                    sdkInt == Build.VERSION_CODES.TIRAMISU -> {
                        listOf(Manifest.permission.READ_MEDIA_IMAGES)
                    }
                    else -> {
                        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }
            SourceType.RECORDING -> {
                // Cayana reads existing audio recordings; does not record via microphone
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            SourceType.DOWNLOAD -> {
                // Downloads uses Storage Access Framework (SAF) folder picker rather than runtime permissions
                emptyList()
            }
            SourceType.SHARED_URL, SourceType.SHARED_TEXT, SourceType.SHARED_IMAGE, SourceType.SHARED_DOCUMENT, SourceType.SHARED_FILE -> {
                emptyList()
            }
        }
    }

    override fun hasLimitedAccess(sourceType: SourceType): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (sourceType == SourceType.SCREENSHOT || sourceType == SourceType.PHOTO) &&
                !hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            false
        }
    }

    override fun isSourceAuthorized(sourceType: SourceType, customUri: String?): Boolean {
        return when (sourceType) {
            SourceType.SCREENSHOT, SourceType.PHOTO -> {
                if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Full authorization requires READ_MEDIA_IMAGES
                    hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                } else if (sdkInt == Build.VERSION_CODES.TIRAMISU) {
                    hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            SourceType.RECORDING -> {
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            SourceType.DOWNLOAD -> {
                isDownloadsAuthorized(customUri)
            }
            SourceType.SHARED_URL, SourceType.SHARED_TEXT, SourceType.SHARED_IMAGE, SourceType.SHARED_DOCUMENT, SourceType.SHARED_FILE -> true
        }
    }

    private fun isDownloadsAuthorized(customUri: String?): Boolean {
        if (customUri.isNullOrBlank()) return false
        return try {
            val targetUri = Uri.parse(customUri)
            context.contentResolver.persistedUriPermissions.any {
                it.uri == targetUri && it.isReadPermission
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun getSourceStatus(
        sourceType: SourceType,
        isEnabled: Boolean,
        isDenied: Boolean,
        customUri: String?
    ): SourceStatus {
        if (!isEnabled) {
            return SourceStatus.DISABLED
        }

        if (isDenied) {
            return SourceStatus.PERMISSION_DENIED
        }

        if (hasLimitedAccess(sourceType)) {
            return SourceStatus.LIMITED_ACCESS
        }

        val authorized = isSourceAuthorized(sourceType, customUri)
        return if (authorized) {
            SourceStatus.ENABLED_AND_AUTHORIZED
        } else {
            SourceStatus.ENABLED_PERMISSION_REQUIRED
        }
    }
}
