package com.cayana.ask.security

import com.cayana.ask.llm.LlmProviderInfo
import com.cayana.ask.model.ContextPack

/**
 * Authorization Gate interface to verify and confirm transmission of ContextPack
 * before it leaves the local boundary to an LLM provider.
 */
interface AskAuthorizationGate {
    /**
     * Inspects ContextPack and determines if external transmission is authorized.
     */
    suspend fun checkAuthorization(
        query: String,
        contextPack: ContextPack,
        providerInfo: LlmProviderInfo
    ): AuthorizationDecision
}

/**
 * Permissive gate for local offline inference, testing, and automated queries.
 */
class PermissiveAuthorizationGate : AskAuthorizationGate {
    override suspend fun checkAuthorization(
        query: String,
        contextPack: ContextPack,
        providerInfo: LlmProviderInfo
    ): AuthorizationDecision = AuthorizationDecision.Allowed
}

/**
 * Interactive / callback-driven gate allowing explicit user inspection before transmission.
 */
class ReviewableAuthorizationGate(
    private val reviewer: suspend (query: String, contextPack: ContextPack, providerInfo: LlmProviderInfo) -> Boolean
) : AskAuthorizationGate {
    override suspend fun checkAuthorization(
        query: String,
        contextPack: ContextPack,
        providerInfo: LlmProviderInfo
    ): AuthorizationDecision {
        val approved = try {
            reviewer(query, contextPack, providerInfo)
        } catch (e: Exception) {
            return AuthorizationDecision.Denied("Authorization review encountered error: ${e.message}")
        }
        return if (approved) {
            AuthorizationDecision.Allowed
        } else {
            AuthorizationDecision.Denied("User declined transmission of ContextPack to provider ${providerInfo.name}")
        }
    }
}
