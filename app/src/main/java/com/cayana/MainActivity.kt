package com.cayana

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.cayana.ui.navigation.CayanaNavHost
import com.cayana.ui.theme.CayanaTheme

class MainActivity : ComponentActivity() {

    private val searchQueryState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            CayanaTheme {
                CayanaNavHost(searchQuery = searchQueryState.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val query = intent?.getStringExtra("cayana_search_query") ?: return
        searchQueryState.value = query
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("search", query))
        } catch (_: Exception) {}
    }
}
