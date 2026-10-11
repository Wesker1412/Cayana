package com.cayana.actions.model

/**
 * Universal routing decisions made by the ActionRouter.
 */
sealed class RoutingDecision {
    /**
     * Memory is retained locally in Cayana without dispatching to any external destination.
     */
    data class MemoryOnly(
        val reason: String,
        val candidate: ActionCandidate? = null
    ) : RoutingDecision()

    /**
     * Confident candidate routed automatically to destination.
     */
    data class RouteAuto(
        val candidate: ActionCandidate,
        val destinationType: DestinationType
    ) : RoutingDecision()

    /**
     * Requires explicit user confirmation before routing (e.g. medium confidence, high false-positive risk).
     */
    data class RequireConfirmation(
        val candidate: ActionCandidate,
        val destinationType: DestinationType,
        val reason: String
    ) : RoutingDecision()

    /**
     * Destination is configured but authentication/authorization has expired or is invalid.
     * Retains memory locally and flags that user attention is required.
     */
    data class NeedsAttention(
        val candidate: ActionCandidate,
        val destinationType: DestinationType,
        val reason: String
    ) : RoutingDecision()
}
