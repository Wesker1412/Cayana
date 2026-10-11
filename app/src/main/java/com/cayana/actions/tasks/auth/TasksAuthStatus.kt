package com.cayana.actions.tasks.auth

enum class TasksAuthStatus {
    DISCONNECTED,
    CONNECTED,
    AUTH_REQUIRED
}

class TasksAuthRequiredException(
    message: String = "Google Tasks 授權已失效或需要使用者確認"
) : Exception(message)
