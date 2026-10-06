package com.cayana.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class ShareChooserSenderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent.getStringExtra("uri")
        val mimeType = intent.getStringExtra("mime") ?: "image/png"

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            if (!uriString.isNullOrBlank()) {
                val uri = if (uriString.startsWith("/") || uriString.startsWith("file://")) {
                    val cleanPath = uriString.removePrefix("file://")
                    val file = java.io.File(cleanPath)
                    androidx.core.content.FileProvider.getUriForFile(
                        this@ShareChooserSenderActivity,
                        "com.cayana.debug.fileprovider",
                        file
                    )
                } else {
                    Uri.parse(uriString)
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("Share", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val chooser = Intent.createChooser(sendIntent, "Share Item")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
        finish()
    }
}
