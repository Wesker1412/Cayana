package com.cayana.ask.security

/**
 * Result of checking whether a ContextPack is authorized to be transmitted.
 */
sealed class AuthorizationDecision {
    object Allowed : AuthorizationDecision()
    data class Denied(val reason: String) : AuthorizationDecision()
}
