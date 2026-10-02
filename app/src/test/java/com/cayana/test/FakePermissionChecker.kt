package com.cayana.test

import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType

class FakePermissionChecker(
    private val grantedPermissions: MutableSet<String> = mutableSetOf(),
    private val persistedUris: MutableSet<String> = mutableSetOf()
) : PermissionChecker {

    var simulateLimitedAccess: Boolean = false

    fun setPermissionGranted(permission: String, granted: Boolean) {
        if (granted) grantedPermissions.add(permission) else grantedPermissions.remove(permission)
    }

    fun setPersistedUri(uriString: String, granted: Boolean) {
        if (granted) persistedUris.add(uriString) else persistedUris.remove(uriString)
    }

    override fun hasPermission(permission: String): Boolean {
        return grantedPermissions.contains(permission)
    }

    override fun getRequiredPermissions(sourceType: SourceType): List<String> {
        return when (sourceType) {
            SourceType.SCREENSHOT, SourceType.PHOTO -> listOf("android.permission.READ_MEDIA_IMAGES")
            SourceType.RECORDING -> listOf("android.permission.READ_MEDIA_AUDIO")
            SourceType.DOWNLOAD -> emptyList()
            else -> emptyList()
        }
    }

    override fun hasLimitedAccess(sourceType: SourceType): Boolean {
        return simulateLimitedAccess && (sourceType == SourceType.SCREENSHOT || sourceType == SourceType.PHOTO)
    }

    override fun isSourceAuthorized(sourceType: SourceType, customUri: String?): Boolean {
        return when (sourceType) {
            SourceType.DOWNLOAD -> {
                customUri != null && persistedUris.contains(customUri)
            }
            SourceType.SCREENSHOT, SourceType.PHOTO -> {
                !simulateLimitedAccess && hasPermission("android.permission.READ_MEDIA_IMAGES")
            }
            SourceType.RECORDING -> {
                hasPermission("android.permission.READ_MEDIA_AUDIO")
            }
            else -> true
        }
    }

    override fun getSourceStatus(
        sourceType: SourceType,
        isEnabled: Boolean,
        isDenied: Boolean,
        customUri: String?
    ): SourceStatus {
        if (!isEnabled) return SourceStatus.DISABLED
        if (isDenied) return SourceStatus.PERMISSION_DENIED
        if (hasLimitedAccess(sourceType)) return SourceStatus.LIMITED_ACCESS
        val authorized = isSourceAuthorized(sourceType, customUri)
        return if (authorized) SourceStatus.ENABLED_AND_AUTHORIZED else SourceStatus.ENABLED_PERMISSION_REQUIRED
    }
}
