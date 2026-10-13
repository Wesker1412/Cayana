package com.cayana.actions.notion.auth

import java.io.IOException

enum class NotionAuthStatus {
    DISCONNECTED,
    CONNECTED,
    AUTH_REQUIRED
}

class NotionAuthRequiredException(message: String = "Notion 授權失效或需要重新登入") : Exception(message)
class NotionRateLimitedException(message: String = "Notion API 429 Too Many Requests") : IOException(message)
class NotionServerException(val statusCode: Int, message: String = "Notion API $statusCode Server Error") : IOException(message)
