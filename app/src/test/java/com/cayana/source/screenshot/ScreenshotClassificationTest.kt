package com.cayana.source.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotClassificationTest {

    @Test
    fun `identifies Google Pixel screenshot correctly`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "Pictures/Screenshots/",
            displayName = "Screenshot_20261002-143000.png",
            bucketDisplayName = "Screenshots",
            mimeType = "image/png"
        )
        assertTrue("Pixel screenshot should be classified as screenshot", result)
    }

    @Test
    fun `identifies Samsung Galaxy screenshot correctly`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "DCIM/Screenshots/",
            displayName = "Screenshot_20261002_143000_Samsung.jpg",
            bucketDisplayName = "Screenshots",
            mimeType = "image/jpeg"
        )
        assertTrue("Samsung screenshot should be classified as screenshot", result)
    }

    @Test
    fun `identifies Xiaomi MIUI screenshot correctly`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "DCIM/Screenshots/",
            displayName = "Screenshot_2026-10-02-14-30-00-000_com.miui.gallery.jpg",
            bucketDisplayName = "Screenshots",
            mimeType = "image/jpeg"
        )
        assertTrue("Xiaomi MIUI screenshot should be classified as screenshot", result)
    }

    @Test
    fun `identifies Huawei screenshot correctly`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "Pictures/Screenshots/",
            displayName = "Screenshot_20261002_143000.jpg",
            bucketDisplayName = "Screenshots",
            mimeType = "image/jpeg"
        )
        assertTrue("Huawei screenshot should be classified as screenshot", result)
    }

    @Test
    fun `identifies OnePlus and Oppo screenshot correctly`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "Pictures/Screenshots/",
            displayName = "Screenshot_2026_10_02_143000.png",
            bucketDisplayName = "Screenshots",
            mimeType = "image/png"
        )
        assertTrue("OnePlus/Oppo screenshot should be classified as screenshot", result)
    }

    @Test
    fun `identifies Traditional and Simplified Chinese bucket names`() {
        assertTrue(
            "Traditional Chinese 螢幕截圖 bucket name should be accepted",
            ScreenshotClassifier.isScreenshot(
                relativePath = null,
                displayName = "img_001.png",
                bucketDisplayName = "螢幕截圖",
                mimeType = "image/png"
            )
        )
        assertTrue(
            "Simplified Chinese 屏幕截图 bucket name should be accepted",
            ScreenshotClassifier.isScreenshot(
                relativePath = null,
                displayName = "img_002.png",
                bucketDisplayName = "屏幕截图",
                mimeType = "image/png"
            )
        )
    }

    @Test
    fun `rejects DCIM Camera photo`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "DCIM/Camera/",
            displayName = "IMG_20261002_143000.jpg",
            bucketDisplayName = "Camera",
            mimeType = "image/jpeg"
        )
        assertFalse("Camera photo should not be classified as screenshot", result)
    }

    @Test
    fun `rejects video and non-image MIME types`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "Pictures/Screenshots/",
            displayName = "Screen_Recording_20261002.mp4",
            bucketDisplayName = "Screenshots",
            mimeType = "video/mp4"
        )
        assertFalse("Video files should be rejected", result)
    }

    @Test
    fun `rejects general Downloads folder image`() {
        val result = ScreenshotClassifier.isScreenshot(
            relativePath = "Download/",
            displayName = "receipt_invoice.jpg",
            bucketDisplayName = "Download",
            mimeType = "image/jpeg"
        )
        assertFalse("Regular download images should not be treated as screenshots", result)
    }
}
