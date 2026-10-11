package com.cayana.ask.security

import com.cayana.ask.llm.LlmProviderInfo
import com.cayana.ask.model.ContextPack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Encapsulates a pending user privacy authorization request before transmission to a remote provider.
 */
data class PendingAuthorizationRequest(
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val contextPack: ContextPack,
    val providerInfo: LlmProviderInfo,
    internal val deferred: CompletableDeferred<Boolean> = CompletableDeferred()
)

/**
 * Authorization gate allowing interactive user inspection and explicit approval/rejection.
 */
interface InteractiveAuthorizationGate : AskAuthorizationGate {
    val pendingRequest: StateFlow<PendingAuthorizationRequest?>
    fun approve(requestId: String)
    fun reject(requestId: String)
}

/**
 * Production implementation of InteractiveAuthorizationGate.
 *
 * Guarantees:
 * 1. If provider is local (isLocal == true), delegates to fallbackLocalGate (PermissiveAuthorizationGate).
 * 2. If provider is remote (isLocal == false), suspends execution, sets pendingRequest for UI presentation,
 *    and awaits explicit user approval.
 * 3. If user cancels or rejects, execution resumes with AuthorizationDecision.Denied, guaranteeing
 *    zero HTTP transmission leaves the device.
 */
class DefaultInteractiveAuthorizationGate(
    private val fallbackLocalGate: AskAuthorizationGate = PermissiveAuthorizationGate()
) : InteractiveAuthorizationGate {

    private val _pendingRequest = MutableStateFlow<PendingAuthorizationRequest?>(null)
    override val pendingRequest: StateFlow<PendingAuthorizationRequest?> = _pendingRequest.asStateFlow()

    override suspend fun checkAuthorization(
        query: String,
        contextPack: ContextPack,
        providerInfo: LlmProviderInfo
    ): AuthorizationDecision {
        if (providerInfo.isLocal) {
            return fallbackLocalGate.checkAuthorization(query, contextPack, providerInfo)
        }

        val deferred = CompletableDeferred<Boolean>()
        val request = PendingAuthorizationRequest(
            query = query,
            contextPack = contextPack,
            providerInfo = providerInfo,
            deferred = deferred
        )
        _pendingRequest.value = request

        val approved = try {
            deferred.await()
        } catch (e: Exception) {
            false
        } finally {
            if (_pendingRequest.value?.id == request.id) {
                _pendingRequest.value = null
            }
        }

        return if (approved) {
            AuthorizationDecision.Allowed
        } else {
            AuthorizationDecision.Denied(
                "User declined transmission of ContextPack to remote provider '${providerInfo.name}'"
            )
        }
    }

    override fun approve(requestId: String) {
        val current = _pendingRequest.value
        if (current != null && current.id == requestId) {
            current.deferred.complete(true)
            _pendingRequest.value = null
        }
    }

    override fun reject(requestId: String) {
        val current = _pendingRequest.value
        if (current != null && current.id == requestId) {
            current.deferred.complete(false)
            _pendingRequest.value = null
        }
    }
}
