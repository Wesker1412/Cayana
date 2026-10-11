package com.cayana.ui.ask

import com.cayana.ask.model.AskResult
import com.cayana.ask.security.PendingAuthorizationRequest
import com.cayana.memory.model.MemoryItem

/**
 * Stages of the Ask pipeline for UI progress presentation.
 */
enum class AskProgressStage(val label: String) {
    IDLE(""),
    LOCAL_RETRIEVAL("正在檢索本機記憶庫……"),
    PACKAGING_CONTEXT("正在封裝 ContextPack 記憶上下文……"),
    AWAITING_AUTHORIZATION("等待隱私傳輸授權……"),
    INFERRING_ANSWER("正在產生回答……"),
    COMPLETED("完成")
}

/**
 * UI State for the Cayana Ask Screen.
 */
data class AskUiState(
    val query: String = "",
    val isQuerying: Boolean = false,
    val progressStage: AskProgressStage = AskProgressStage.IDLE,
    val result: AskResult? = null,
    val errorMessage: String? = null,
    val isNetworkError: Boolean = false,
    val pendingAuthorization: PendingAuthorizationRequest? = null,
    val selectedDetailMemory: MemoryItem? = null,
    val useRemoteProvider: Boolean = false,
    val isRemoteAvailable: Boolean = false,
    val activeProviderName: String = "示範推論 (未搭載本機 LLM)"
)
