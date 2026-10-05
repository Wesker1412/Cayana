package com.cayana.source.photo

import com.cayana.source.SourceItem
import com.cayana.source.screenshot.ScreenshotClassifier

/**
 * Classifies whether a MediaStore image is a legitimate Photo candidate for Cayana.
 * Strictly excludes Screenshots, pending/trashed files, downloads, and unsupported formats.
 */
object PhotoClassifier {

    private val SUPPORTED_IMAGE_MIMES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/heic",
        "image/heif"
    )

    /**
     * Evaluates MediaStore image attributes.
     */
    fun isPhoto(
        relativePath: String?,
        displayName: String?,
        bucketDisplayName: String?,
        mimeType: String?,
        isPending: Boolean = false,
        isTrashed: Boolean = false
    ): Boolean {
        // 1. In-flight pending writes or trashed items are rejected
        if (isPending || isTrashed) {
            return false
        }

        // 2. MIME type verification
        val cleanMime = mimeType?.lowercase()?.trim() ?: ""
        if (cleanMime.isBlank() || !cleanMime.startsWith("image/")) {
            return false
        }
        if (!SUPPORTED_IMAGE_MIMES.contains(cleanMime)) {
            return false
        }

        // 3. Exclude Screenshots! Screenshots are handled exclusively by Screenshot pipeline.
        if (ScreenshotClassifier.isScreenshot(relativePath, displayName, bucketDisplayName, mimeType)) {
            return false
        }

        val path = relativePath?.lowercase()?.trim() ?: ""
        val bucket = bucketDisplayName?.lowercase()?.trim() ?: ""
        val name = displayName?.lowercase()?.trim() ?: ""

        // 4. Exclude obvious Downloads
        if (path.contains("download") || bucket.contains("download")) {
            return false
        }

        return true
    }

    fun isPhoto(item: SourceItem): Boolean {
        val relPath = item.metadata["relativePath"]
        val displayName = item.metadata["displayName"]
        val bucket = item.metadata["bucketDisplayName"]
        return isPhoto(relPath, displayName, bucket, item.mimeType)
    }
}
