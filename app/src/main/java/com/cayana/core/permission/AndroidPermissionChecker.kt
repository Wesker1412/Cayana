package com.cayana.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.cayana.source.SourceType

/**
 * Android implementation of PermissionChecker adhering to Android version-specific
 * permission models (API 34+ Selected Media Access, API 33 Granular Media Permissions, API <=32 External Storage).
 */
class AndroidPermissionChecker(
    private val context: Context
) : PermissionChecker {

    override fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun getRequiredPermissions(sourceType: SourceType): List<String> {
        return when (sourceType) {
            SourceType.SCREENSHOT, SourceType.PHOTO -> {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        // Android 14+ supports partial/selected access
                        listOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    }
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU -> {
                        listOf(Manifest.permission.READ_MEDIA_IMAGES)
                    }
                    else -> {
                        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }
            SourceType.RECORDING -> {
                listOf(Manifest.permission.RECORD_AUDIO)
            }
            SourceType.DOWNLOAD -> {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    // API 33+ uses app-specific storage or SAF for downloads without external storage permission
                    emptyList()
                }
            }
            SourceType.SHARED_URL, SourceType.SHARED_TEXT, SourceType.SHARED_FILE -> {
                emptyList()
            }
        }
    }

    override fun isSourceAuthorized(sourceType: SourceType): Boolean {
        val permissions = getRequiredPermissions(sourceType)
        if (permissions.isEmpty()) return true

        return when (sourceType) {
            SourceType.SCREENSHOT, SourceType.PHOTO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // On Android 14+, either full access OR user-selected partial access counts as authorized
                    hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                        hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                } else {
                    permissions.all { hasPermission(it) }
                }
            }
            else -> {
                permissions.all { hasPermission(it) }
            }
        }
    }

    override fun getSourceStatus(
        sourceType: SourceType,
        isEnabled: Boolean,
        isDenied: Boolean
    ): SourceStatus {
        if (!isEnabled) {
            return SourceStatus.DISABLED
        }

        if (isDenied) {
            return SourceStatus.PERMISSION_DENIED
        }

        val authorized = isSourceAuthorized(sourceType)
        return if (authorized) {
            SourceStatus.ENABLED_AND_AUTHORIZED
        } else {
            SourceStatus.ENABLED_PERMISSION_REQUIRED
        }
    }
}
