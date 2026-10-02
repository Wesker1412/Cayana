package com.cayana.source.screenshot

import com.cayana.source.SourceItem

/**
 * Classifies whether a MediaStore image item is a Screenshot across multiple Android OEMs
 * (Google Pixel, Samsung, Xiaomi, Huawei, OnePlus, Sony, Motorola, etc.).
 */
object ScreenshotClassifier {

    private val SCREENSHOT_KEYWORDS = listOf(
        "screenshot",
        "screen_shot",
        "screencapture",
        "screen_capture",
        "capture_",
        "屏幕截图",
        "螢幕截圖",
        "截圖"
    )

    private val CAMERA_PREFIXES = listOf(
        "img_",
        "pxl_",
        "vid_",
        "dsc_",
        "dji_",
        "wp_",
        "mvimg_"
    )

    /**
     * Determines whether the given MediaStore attributes identify a Screenshot with high confidence.
     */
    fun isScreenshot(
        relativePath: String?,
        displayName: String?,
        bucketDisplayName: String?,
        mimeType: String?
    ): Boolean {
        // 1. MIME type validation
        if (mimeType != null && !mimeType.startsWith("image/", ignoreCase = true)) {
            return false
        }

        val name = displayName?.lowercase()?.trim() ?: ""
        val path = relativePath?.lowercase()?.trim() ?: ""
        val bucket = bucketDisplayName?.lowercase()?.trim() ?: ""

        // 2. Reject typical Camera outputs unless explicitly containing screenshot keyword
        val isExplicitScreenshot = containsScreenshotKeyword(name) ||
            containsScreenshotKeyword(path) ||
            containsScreenshotKeyword(bucket)

        if (!isExplicitScreenshot) {
            val isCameraPath = path.contains("dcim/camera") || path.contains("pictures/camera")
            val isCameraName = CAMERA_PREFIXES.any { name.startsWith(it) }
            if (isCameraPath || isCameraName) {
                return false
            }
        }

        // 3. Positive verification: Check path, bucket, or display name
        if (containsScreenshotKeyword(path)) return true
        if (containsScreenshotKeyword(bucket)) return true
        if (containsScreenshotKeyword(name)) return true

        return false
    }

    fun isScreenshot(item: SourceItem): Boolean {
        val relPath = item.metadata["relativePath"]
        val displayName = item.metadata["displayName"]
        val bucket = item.metadata["bucketDisplayName"]
        return isScreenshot(relPath, displayName, bucket, item.mimeType)
    }

    private fun containsScreenshotKeyword(input: String): Boolean {
        if (input.isBlank()) return false
        return SCREENSHOT_KEYWORDS.any { keyword -> input.contains(keyword) }
    }
}
