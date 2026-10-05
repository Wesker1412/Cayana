package com.cayana.source

enum class SourceType(val displayName: String) {
    SCREENSHOT("Screenshot"),
    PHOTO("Photo"),
    RECORDING("Recording"),
    DOWNLOAD("Download"),
    SHARED_URL("Shared URL"),
    SHARED_TEXT("Shared Text"),
    SHARED_IMAGE("Shared Image"),
    SHARED_DOCUMENT("Shared Document"),
    SHARED_FILE("Shared File");

    val isShared: Boolean
        get() = this in setOf(SHARED_URL, SHARED_TEXT, SHARED_IMAGE, SHARED_DOCUMENT, SHARED_FILE)
}
