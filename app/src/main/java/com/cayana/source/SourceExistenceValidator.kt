package com.cayana.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.FileNotFoundException

/**
 * Validator to check whether an external source media file still exists on the device,
 * distinguishing definitive deletion (Missing) from permission revocation or transient
 * I/O failure (Unavailable).
 */
open class SourceExistenceValidator(
    private val context: Context,
    private val permissionChecker: com.cayana.core.permission.PermissionChecker? = null
) {

    open fun checkSourceExistence(uriString: String?, sourceType: SourceType? = null): SourceExistence {
        if (uriString.isNullOrBlank()) return SourceExistence.Missing

        // 1. Strict Permission Boundary Check
        if (sourceType != null) {
            val permissionGranted = checkPermissionForSource(sourceType)
            if (!permissionGranted) {
                return SourceExistence.Unavailable(
                    SecurityException("Required permission for $sourceType is revoked")
                )
            }
        }

        // 2. Query / Open descriptor
        return try {
            val uri = Uri.parse(uriString)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfd.close()
                SourceExistence.Exists
            } else {
                SourceExistence.Missing
            }
        } catch (e: FileNotFoundException) {
            SourceExistence.Missing
        } catch (e: SecurityException) {
            SourceExistence.Unavailable(e)
        } catch (e: IllegalStateException) {
            SourceExistence.Unavailable(e)
        } catch (e: Exception) {
            // Other transient ContentProvider / storage errors
            SourceExistence.Unavailable(e)
        }
    }

    private fun checkPermissionForSource(sourceType: SourceType): Boolean {
        if (permissionChecker != null) {
            return permissionChecker.isSourceAuthorized(sourceType)
        }
        return try {
            when (sourceType) {
                SourceType.SCREENSHOT, SourceType.PHOTO -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_MEDIA_IMAGES
                        ) == PackageManager.PERMISSION_GRANTED
                    } else {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                }
                SourceType.RECORDING -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_MEDIA_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    } else {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                }
                else -> true
            }
        } catch (_: Exception) {
            false
        }
    }

    open fun doesSourceExist(uriString: String?): Boolean {
        return checkSourceExistence(uriString) is SourceExistence.Exists
    }
}
