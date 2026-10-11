package com.cayana.actions.tasks.auth

import android.content.Intent
import android.content.IntentSender
import com.cayana.core.common.Result
import kotlinx.coroutines.flow.StateFlow

interface TasksAuthorizationManager {
    val authStatus: StateFlow<TasksAuthStatus>
    suspend fun getAccessToken(): Result<String>
    suspend fun getAuthorizationIntentSender(): Result<IntentSender?>
    suspend fun handleAuthorizationResult(data: Intent?): Result<Unit>
    suspend fun invalidateCachedAccessToken()
    suspend fun disconnect(): Result<Unit>
    fun markAuthRequired()
}
