package com.cayana.test

import com.cayana.core.permission.PermissionChecker
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType

class FakePermissionChecker(
    private val grantedPermissions: MutableSet<String> = mutableSetOf()
) : PermissionChecker {

    fun setPermissionGranted(permission: String, granted: Boolean) {
        if (granted) grantedPermissions.add(permission) else grantedPermissions.remove(permission)
    }

    override fun hasPermission(permission: String): Boolean {
        return grantedPermissions.contains(permission)
    }

    override fun getRequiredPermissions(sourceType: SourceType): List<String> {
        return when (sourceType) {
            SourceType.SCREENSHOT, SourceType.PHOTO -> listOf("android.permission.READ_MEDIA_IMAGES")
            SourceType.RECORDING -> listOf("android.permission.RECORD_AUDIO")
            SourceType.DOWNLOAD -> emptyList()
            else -> emptyList()
        }
    }

    override fun isSourceAuthorized(sourceType: SourceType): Boolean {
        val perms = getRequiredPermissions(sourceType)
        return perms.isEmpty() || perms.all { hasPermission(it) }
    }

    override fun getSourceStatus(
        sourceType: SourceType,
        isEnabled: Boolean,
        isDenied: Boolean
    ): SourceStatus {
        if (!isEnabled) return SourceStatus.DISABLED
        if (isDenied) return SourceStatus.PERMISSION_DENIED
        val authorized = isSourceAuthorized(sourceType)
        return if (authorized) SourceStatus.ENABLED_AND_AUTHORIZED else SourceStatus.ENABLED_PERMISSION_REQUIRED
    }
}
