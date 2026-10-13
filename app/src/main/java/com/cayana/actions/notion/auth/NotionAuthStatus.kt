package com.cayana.actions.notion.auth

enum class NotionAuthStatus {
    DISCONNECTED,
    CONNECTED,
    AUTH_REQUIRED
}

class NotionAuthRequiredException(message: String = "Notion 授權失效或需要重新登入") : Exception(message)
