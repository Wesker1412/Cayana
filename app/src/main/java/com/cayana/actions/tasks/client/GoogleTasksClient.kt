package com.cayana.actions.tasks.client

import com.cayana.core.common.Result

data class GoogleTaskList(
    val id: String,
    val title: String,
    val updated: String? = null
)

data class GoogleTaskItem(
    val id: String,
    val title: String,
    val notes: String? = null,
    val due: String? = null,
    val status: String? = null
)

sealed class TasksHttpException(val statusCode: Int, message: String) : Exception("HTTP $statusCode: $message") {
    class Unauthorized(code: Int, message: String) : TasksHttpException(code, message)
    class RateLimited(code: Int, message: String) : TasksHttpException(code, message)
    class ServerError(code: Int, message: String) : TasksHttpException(code, message)
    class ClientError(code: Int, message: String) : TasksHttpException(code, message)
}

interface GoogleTasksClient {
    suspend fun getTaskLists(accessToken: String): Result<List<GoogleTaskList>>

    suspend fun createTask(
        accessToken: String,
        taskListId: String,
        title: String,
        notes: String?,
        dueRfc3339: String?
    ): Result<GoogleTaskItem>

    suspend fun findTaskByMemoryRef(
        accessToken: String,
        taskListId: String,
        sourceMemoryId: String,
        title: String
    ): Result<GoogleTaskItem?>
}
