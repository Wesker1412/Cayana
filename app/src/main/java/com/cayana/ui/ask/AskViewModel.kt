package com.cayana.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.AskResult
import com.cayana.ask.remote.RemoteInferenceException
import com.cayana.ask.security.InteractiveAuthorizationGate
import com.cayana.ask.service.AskService
import com.cayana.memory.repository.MemoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * ViewModel managing the Ask query flow, privacy preview & authorization,
 * cited sources inspection, and network retry mechanisms.
 */
class AskViewModel(
    private val localAskService: AskService,
    private val remoteAskService: AskService,
    private val authorizationGate: InteractiveAuthorizationGate,
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskUiState())
    val uiState: StateFlow<AskUiState> = _uiState.asStateFlow()

    private var currentQueryJob: Job? = null

    init {
        _uiState.update {
            it.copy(
                isRemoteAvailable = remoteAskService.isEnabled,
                activeProviderName = getProviderDisplayName(it.useRemoteProvider)
            )
        }

        // Observe pending authorization requests emitted by the authorization gate
        viewModelScope.launch {
            authorizationGate.pendingRequest.collect { request ->
                _uiState.update { current ->
                    if (request != null) {
                        current.copy(
                            pendingAuthorization = request,
                            progressStage = AskProgressStage.AWAITING_AUTHORIZATION
                        )
                    } else {
                        current.copy(
                            pendingAuthorization = null,
                            progressStage = if (current.isQuerying) AskProgressStage.INFERRING_ANSWER else current.progressStage
                        )
                    }
                }
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update {
            it.copy(
                query = newQuery,
                errorMessage = null,
                isNetworkError = false
            )
        }
    }

    fun onToggleRemoteProvider(enabled: Boolean) {
        // Requirement 4: 查詢進行中禁止切換 Provider
        if (_uiState.value.isQuerying) return

        // Requirement 3: 未部署遠端服務必須停用
        if (enabled && !remoteAskService.isEnabled) {
            _uiState.update {
                it.copy(
                    errorMessage = "遠端推論服務尚未正式部署啟用，目前僅提供示範模式。"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                useRemoteProvider = enabled,
                activeProviderName = getProviderDisplayName(enabled),
                errorMessage = null
            )
        }
    }

    fun onAsk() {
        // Requirement 4: 查詢進行中不允許重複 onAsk()
        if (_uiState.value.isQuerying) return

        val trimmed = _uiState.value.query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(
                    errorMessage = "請輸入問題內容",
                    isNetworkError = false
                )
            }
            return
        }

        // Requirement 4: 每次提問固定使用提交瞬間選定的 Provider
        val useRemoteAtSubmission = _uiState.value.useRemoteProvider
        if (useRemoteAtSubmission && !remoteAskService.isEnabled) {
            _uiState.update {
                it.copy(
                    errorMessage = "遠端推論服務尚未正式部署啟用，無法送出遠端提問。"
                )
            }
            return
        }

        val service = if (useRemoteAtSubmission) remoteAskService else localAskService

        _uiState.update {
            it.copy(
                isQuerying = true,
                progressStage = AskProgressStage.LOCAL_RETRIEVAL,
                result = null,
                errorMessage = null,
                isNetworkError = false,
                pendingAuthorization = null
            )
        }

        currentQueryJob = viewModelScope.launch {
            val askResult = service.ask(trimmed, AskOptions())
            handleAskResult(askResult)
        }
    }

    fun onApproveAuthorization() {
        val pending = _uiState.value.pendingAuthorization ?: return
        _uiState.update {
            it.copy(
                progressStage = AskProgressStage.INFERRING_ANSWER
            )
        }
        authorizationGate.approve(pending.id)
    }

    fun onRejectAuthorization() {
        val pending = _uiState.value.pendingAuthorization ?: return
        authorizationGate.reject(pending.id)
        currentQueryJob?.cancel()
        _uiState.update {
            it.copy(
                isQuerying = false,
                progressStage = AskProgressStage.IDLE,
                pendingAuthorization = null,
                errorMessage = "已取消遠端傳輸，未送出任何資料。"
            )
        }
    }

    fun onCancelQuery() {
        authorizationGate.cancelPending()
        currentQueryJob?.cancel()
        _uiState.update {
            it.copy(
                isQuerying = false,
                progressStage = AskProgressStage.IDLE,
                pendingAuthorization = null,
                errorMessage = "已取消傳輸，未送出任何資料。"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        authorizationGate.cancelPending()
        currentQueryJob?.cancel()
    }

    fun onRetry() {
        onAsk()
    }

    fun onSelectSourceMemory(memoryId: String) {
        viewModelScope.launch {
            val memory = memoryRepository.getMemoryById(memoryId).firstOrNull()
            if (memory != null) {
                _uiState.update { it.copy(selectedDetailMemory = memory) }
            }
        }
    }

    fun onDismissMemoryDetail() {
        _uiState.update { it.copy(selectedDetailMemory = null) }
    }

    private fun handleAskResult(askResult: AskResult) {
        when (askResult) {
            is AskResult.Success -> {
                _uiState.update {
                    it.copy(
                        isQuerying = false,
                        progressStage = AskProgressStage.COMPLETED,
                        result = askResult,
                        errorMessage = null,
                        isNetworkError = false
                    )
                }
            }
            is AskResult.NoEvidence -> {
                _uiState.update {
                    it.copy(
                        isQuerying = false,
                        progressStage = AskProgressStage.COMPLETED,
                        result = askResult,
                        errorMessage = null,
                        isNetworkError = false
                    )
                }
            }
            is AskResult.Unauthorized -> {
                val msg = if (askResult.reason.contains("declined", ignoreCase = true) ||
                    askResult.reason.contains("取消")
                ) {
                    "已取消遠端傳輸，未送出任何資料。"
                } else {
                    "遠端傳輸未獲授權：${askResult.reason}"
                }
                _uiState.update {
                    it.copy(
                        isQuerying = false,
                        progressStage = AskProgressStage.IDLE,
                        result = askResult,
                        errorMessage = msg,
                        isNetworkError = false
                    )
                }
            }
            is AskResult.Error -> {
                val isNetwork = isNetworkIssue(askResult.cause, askResult.message)
                _uiState.update {
                    it.copy(
                        isQuerying = false,
                        progressStage = AskProgressStage.IDLE,
                        result = askResult,
                        errorMessage = if (isNetwork) {
                            "網路連線異常，請檢查連線或稍後重試。"
                        } else {
                            askResult.message
                        },
                        isNetworkError = isNetwork
                    )
                }
            }
        }
    }

    private fun getProviderDisplayName(useRemote: Boolean): String {
        return if (useRemote) {
            "Cayana Remote LLM API"
        } else {
            "示範推論 (未搭載本機 LLM)"
        }
    }

    private fun isNetworkIssue(cause: Throwable?, message: String): Boolean {
        if (cause is IOException ||
            cause is RemoteInferenceException.NetworkError ||
            cause is RemoteInferenceException.UpstreamError
        ) {
            return true
        }
        val lower = message.lowercase()
        return lower.contains("network") ||
            lower.contains("timeout") ||
            lower.contains("connect") ||
            lower.contains("connection") ||
            lower.contains("502") ||
            lower.contains("503") ||
            lower.contains("offline")
    }
}
