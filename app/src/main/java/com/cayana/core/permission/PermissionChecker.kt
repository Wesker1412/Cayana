package com.cayana.core.permission

import com.cayana.source.SourceType

/**
 * Interface for checking OS runtime permissions per source type,
 * taking into account Android API level nuances.
 */
interface PermissionChecker {
    /**
     * Checks if a specific OS permission is granted.
     */
    fun hasPermission(permission: String): Boolean

    /**
     * Returns the list of runtime permissions needed to operate a given SourceType
     * on the current Android version.
     */
    fun getRequiredPermissions(sourceType: SourceType): List<String>

    /**
     * Checks if the required OS permissions for a given SourceType are fully satisfied.
     */
    fun isSourceAuthorized(sourceType: SourceType, customUri: String? = null): Boolean

    /**
     * Checks if a source has partial/limited access (e.g. Android 14+ Selected Photos).
     */
    fun hasLimitedAccess(sourceType: SourceType): Boolean

    /**
     * Computes the combined SourceStatus from the user preference, OS permission state, and optional SAF URI.
     */
    fun getSourceStatus(
        sourceType: SourceType,
        isEnabled: Boolean,
        isDenied: Boolean = false,
        customUri: String? = null
    ): SourceStatus
}
