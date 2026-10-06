package com.cayana.source.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ShareReceiverActivity : ComponentActivity() {

    private val shareProcessor: ShareProcessor by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = intent
        if (targetIntent == null) {
            finish()
            return
        }
        handleIntent(targetIntent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(targetIntent: Intent) {
        lifecycleScope.launch {
            try {
                val result = shareProcessor.processIntent(targetIntent)
                when (result) {
                    is ShareIngestResult.Success -> {
                        Toast.makeText(this@ShareReceiverActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                    is ShareIngestResult.PartialSuccess -> {
                        Toast.makeText(this@ShareReceiverActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                    is ShareIngestResult.Duplicate -> {
                        Toast.makeText(this@ShareReceiverActivity, "已記住", Toast.LENGTH_SHORT).show()
                    }
                    is ShareIngestResult.Ignored -> {
                        // Do not show errors for ignored/unsupported intents
                    }
                }
            } catch (e: Exception) {
                CayanaLogger.w("ShareReceiver", "Error processing share intent: ${e.javaClass.simpleName}")
            } finally {
                finish()
            }
        }
    }
}
