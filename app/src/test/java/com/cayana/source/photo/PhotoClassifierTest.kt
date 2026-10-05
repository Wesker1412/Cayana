package com.cayana.source.photo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoClassifierTest {

    @Test
    fun normalCameraPhotoIsClassifiedAsPhoto() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "DCIM/Camera/",
            displayName = "IMG_20261005_120000.jpg",
            bucketDisplayName = "Camera",
            mimeType = "image/jpeg"
        )
        assertTrue(result)
    }

    @Test
    fun customFolderPhotoIsClassifiedAsPhoto() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "Pictures/MyTrip/",
            displayName = "photo_taipei.jpg",
            bucketDisplayName = "MyTrip",
            mimeType = "image/jpeg"
        )
        assertTrue(result)
    }

    @Test
    fun pngCameraPhotoIsClassifiedAsPhoto() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "DCIM/Camera/",
            displayName = "IMG_photo.png",
            bucketDisplayName = "Camera",
            mimeType = "image/png"
        )
        assertTrue(result)
    }

    @Test
    fun screenshotImageIsExcludedFromPhoto() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "Pictures/Screenshots/",
            displayName = "Screenshot_20261005-120000.png",
            bucketDisplayName = "Screenshots",
            mimeType = "image/png"
        )
        assertFalse(result)
    }

    @Test
    fun screenshotKeywordInNameIsExcluded() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "DCIM/Camera/",
            displayName = "screenshot_ticket.jpg",
            bucketDisplayName = "Camera",
            mimeType = "image/jpeg"
        )
        assertFalse(result)
    }

    @Test
    fun downloadFolderImageIsExcluded() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "Download/",
            displayName = "voucher.jpg",
            bucketDisplayName = "Download",
            mimeType = "image/jpeg"
        )
        assertFalse(result)
    }

    @Test
    fun nonImageMimeIsRejected() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "DCIM/Camera/",
            displayName = "recording.mp3",
            bucketDisplayName = "Camera",
            mimeType = "audio/mpeg"
        )
        assertFalse(result)
    }

    @Test
    fun pendingImageIsRejected() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "DCIM/Camera/",
            displayName = "IMG_2026.jpg",
            bucketDisplayName = "Camera",
            mimeType = "image/jpeg",
            isPending = true
        )
        assertFalse(result)
    }

    @Test
    fun trashedImageIsRejected() {
        val result = PhotoClassifier.isPhoto(
            relativePath = "DCIM/Camera/",
            displayName = "IMG_2026.jpg",
            bucketDisplayName = "Camera",
            mimeType = "image/jpeg",
            isTrashed = true
        )
        assertFalse(result)
    }
}
