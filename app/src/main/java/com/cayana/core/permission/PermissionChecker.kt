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
     * Checks if the required OS permissions for a given SourceType are satisfied.
     */
    fun isSourceAuthorized(sourceType: SourceType): Boolean

    /**
     * Computes the combined SourceStatus from the user preference and OS permission state.
     */
    fun getSourceStatus(
        sourceType: SourceType,
        isEnabled: Boolean,
        isDenied: Boolean = false
    ): SourceStatus
}
