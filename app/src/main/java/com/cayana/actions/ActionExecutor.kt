package com.cayana.actions

enum class ActionType {
    CREATE_CALENDAR_EVENT,
    SHOW_NOTIFICATION,
    UNDO_ACTION
}

data class ActionResult(
    val actionType: ActionType,
    val success: Boolean,
    val message: String
)

interface ActionExecutor {
    suspend fun execute(actionType: ActionType, payload: Map<String, String>): ActionResult
}

class StubActionExecutor : ActionExecutor {
    override suspend fun execute(actionType: ActionType, payload: Map<String, String>): ActionResult {
        return ActionResult(
            actionType = actionType,
            success = false,
            message = "Action execution is not enabled in Stage 0"
        )
    }
}
