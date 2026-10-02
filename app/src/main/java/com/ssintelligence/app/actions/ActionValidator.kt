package com.ssintelligence.app.actions

import com.ssintelligence.app.assistant.SensitivityLevel

/**
 * Validates an action before it can be shown or executed (§58, §60).
 *
 * The validator is the safety boundary: it checks required fields, permission
 * status, sensitivity and confirmation requirements. No action reaches the UI or
 * an Android API without passing here. AI-generated text can propose an action,
 * but only a typed, validated command can execute.
 */
object ActionValidator {

    data class Request(
        val action: ContextAction,
        val permissionGranted: Boolean,
        val sensitivity: SensitivityLevel,
    )

    data class Result(val valid: Boolean, val reason: String?) {
        companion object {
            val OK = Result(true, null)
        }
    }

    fun validate(request: Request): Result {
        val action = request.action

        // Required fields must be present and well-formed.
        if (action.title.isBlank()) return Result(false, "Action has no title")
        if (action.screenshotIds.isEmpty() && action.payload !is ActionPayload.Collection) {
            return Result(false, "Action is not tied to a screenshot")
        }
        if (!payloadValid(action.payload)) {
            return Result(false, "Action payload is incomplete")
        }

        // Permission check: only at the moment the action is needed.
        if (action.permission != PermissionType.NONE && !request.permissionGranted) {
            return Result(false, "Permission not granted: ${action.permission}")
        }

        // Sensitivity: sensitive actions always require confirmation, and are
        // never allowed to run as automatic rule actions.
        if (action.sensitivity == SensitivityLevel.HIGHLY_SENSITIVE &&
            action.confirmation != ConfirmationLevel.SENSITIVE
        ) {
            return Result(false, "Highly sensitive action must require confirmation")
        }

        return Result.OK
    }

    private fun payloadValid(payload: ActionPayload): Boolean = when (payload) {
        is ActionPayload.Url -> payload.url.isNotBlank() && payload.url.startsWith("http")
        is ActionPayload.Text -> payload.text.isNotBlank()
        is ActionPayload.Phone -> payload.number.any { it.isDigit() }
        is ActionPayload.Email -> payload.address.contains("@")
        is ActionPayload.MapQuery -> payload.query.isNotBlank()
        is ActionPayload.CalendarEvent -> payload.title.isNotBlank() && payload.startEpochMillis > 0
        is ActionPayload.Reminder -> payload.title.isNotBlank()
        is ActionPayload.Expense -> payload.amount > 0 && payload.currency.isNotBlank()
        is ActionPayload.Collection -> payload.name.isNotBlank()
        is ActionPayload.ScreenshotIds -> payload.ids.isNotEmpty()
        is ActionPayload.EntityRef -> payload.entityId > 0
    }

    /**
     * Whether this action is allowed to run as an automatic rule action (§34).
     *
     * Deletion, calls, messages and sharing are never automatic — they always
     * require explicit user interaction.
     */
    fun allowedInAutomation(type: ActionType): Boolean = type !in setOf(
        ActionType.CALL_PHONE,
        ActionType.SEND_MESSAGE,
        ActionType.SHARE,
        ActionType.ARCHIVE, // archive is reversible but still user-initiated
    )
}
