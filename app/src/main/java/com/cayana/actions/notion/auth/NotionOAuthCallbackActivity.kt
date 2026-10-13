package com.cayana.actions.notion.auth

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.cayana.core.logging.CayanaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NotionOAuthCallbackActivity : Activity(), KoinComponent {

    companion object {
        private const val TAG = "NotionOAuthCallback"
    }

    private val notionAuthManager: NotionAuthorizationManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            CayanaLogger.w(TAG, "NotionOAuthCallbackActivity opened without URI data")
            finish()
            return
        }

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val sanitizedCode = error.take(64).replace(Regex("[^a-zA-Z0-9_]"), "")
            CayanaLogger.w(TAG, "Notion OAuth returned error: $sanitizedCode")
            CoroutineScope(Dispatchers.Main).launch {
                notionAuthManager.handleAuthorizationFailure(sanitizedCode)
                Toast.makeText(this@NotionOAuthCallbackActivity, "Notion 授權未完成", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }

        val ticket = uri.getQueryParameter("ticket") ?: uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")

        if (ticket.isNullOrBlank() || state.isNullOrBlank()) {
            CayanaLogger.w(TAG, "Notion OAuth callback missing ticket or state")
            CoroutineScope(Dispatchers.Main).launch {
                notionAuthManager.handleAuthorizationFailure("MISSING_TICKET_OR_STATE")
                finish()
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = notionAuthManager.handleAuthorizationCallback(ticket, state)
            withContext(Dispatchers.Main) {
                if (result is com.cayana.core.common.Result.Success) {
                    Toast.makeText(this@NotionOAuthCallbackActivity, "Notion 連線成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@NotionOAuthCallbackActivity, "Notion 驗證失敗，請重試", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }
}
