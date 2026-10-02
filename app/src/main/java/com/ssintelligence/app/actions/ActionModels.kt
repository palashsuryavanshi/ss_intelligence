package com.ssintelligence.app.actions

import com.ssintelligence.app.assistant.SensitivityLevel

/**
 * Phase 7 contextual action layer.
 *
 * The app no longer only understands screenshots — it helps act on them. Every
 * action is a typed, validated command that passes through a safety validator
 * and requires user confirmation when consequential. Nothing is executed
 * automatically, and no AI-generated text can reach an Android API directly.
 */

/** What an action does. Closed set — new actions are added deliberately. */
enum class ActionType(val label: String) {
    OPEN_URL("Open URL"),
    COPY_TEXT("Copy text"),
    COPY_PHONE("Copy phone number"),
    CALL_PHONE("Call"),
    SEND_MESSAGE("Message"),
    OPEN_MAP("Open in Maps"),
    EMAIL("Email"),
    CREATE_CALENDAR_EVENT("Add to Calendar"),
    CREATE_REMINDER("Create reminder"),
    SAVE_EXPENSE("Save expense"),
    CREATE_NOTE("Create note"),
    CREATE_COLLECTION("Create collection"),
    COMPARE_SCREENSHOTS("Compare"),
    SEARCH_RELATED("Search related"),
    SHOW_TIMELINE("Show timeline"),
    SHOW_ENTITY("Show entity"),
    ARCHIVE("Archive"),
    SHARE("Share"),
}

/** Whether an action needs the user's explicit confirmation. */
enum class ConfirmationLevel {
    /** Safe and immediate: open a screenshot, show related, copy non-sensitive text. */
    NONE,
    /** Consequential but reversible: create a reminder, save an expense, add to a collection. */
    CONFIRM,
    /** Sensitive or external: call, message, share, copy an OTP. Always confirmed. */
    SENSITIVE,
}

/** What permission an action needs, if any. */
enum class PermissionType { NONE, CALENDAR, CONTACTS, PHONE, SMS, NOTIFICATIONS }

/**
 * Maps an action permission to its Android manifest permission.
 *
 * Intent-delegated actions (dialer, calendar insert, share sheet) return null:
 * the system app that receives the intent owns the permission, so requesting
 * it here would ask for something the app never uses. Direct API calls, when
 * they exist in the future, return the real permission and are requested only
 * at the moment the action needs them.
 */
fun PermissionType.toAndroidPermission(): String? = when (this) {
    PermissionType.NONE -> null
    PermissionType.CALENDAR -> android.Manifest.permission.WRITE_CALENDAR
    PermissionType.CONTACTS -> android.Manifest.permission.WRITE_CONTACTS
    PermissionType.PHONE -> android.Manifest.permission.CALL_PHONE
    PermissionType.SMS -> android.Manifest.permission.SEND_SMS
    PermissionType.NOTIFICATIONS -> android.Manifest.permission.POST_NOTIFICATIONS
}

/**
 * One actionable thing the app can do with a screenshot's detected content.
 *
 * Generated from actual detected data, ranked by relevance, and always
 * validated before execution. An action that cannot apply is never built.
 */
data class ContextAction(
    val id: String,
    val type: ActionType,
    val title: String,
    val description: String?,
    val screenshotIds: List<Long>,
    val entityIds: List<Long>,
    val confirmation: ConfirmationLevel,
    val permission: PermissionType,
    val sensitivity: SensitivityLevel,
    /** The typed payload — a URL, a phone number, a date, an amount. */
    val payload: ActionPayload,
)

/** The typed data an action operates on. */
sealed interface ActionPayload {
    data class Url(val url: String) : ActionPayload
    data class Text(val text: String, val sensitive: Boolean = false) : ActionPayload
    data class Phone(val number: String) : ActionPayload
    data class Email(val address: String) : ActionPayload
    data class MapQuery(val query: String) : ActionPayload
    data class CalendarEvent(
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long?,
        val location: String?,
        val description: String?,
    ) : ActionPayload

    data class Reminder(
        val title: String,
        val dueEpochMillis: Long?,
        val sourceScreenshotId: Long,
    ) : ActionPayload

    data class Expense(
        val merchant: String?,
        val amount: Double,
        val currency: String,
        val dateEpochDay: Long,
        val category: String?,
    ) : ActionPayload

    data class Collection(val name: String) : ActionPayload
    data class ScreenshotIds(val ids: List<Long>) : ActionPayload
    data class EntityRef(val entityId: Long) : ActionPayload
}

/** The result of executing an action. Never a fake success. */
data class ActionResult(
    val actionId: String,
    val success: Boolean,
    val message: String,
    /** True when the user can undo this action. */
    val undoable: Boolean = false,
)

/** A locally stored reminder referencing its source screenshot (§13). */
data class LocalReminder(
    val id: Long,
    val title: String,
    val dueEpochMillis: Long?,
    val sourceScreenshotId: Long,
    val createdAt: Long,
)

/** A locally saved expense referencing its source screenshot (§16). */
data class ExpenseRecord(
    val id: Long,
    val screenshotId: Long,
    val merchant: String?,
    val amount: Double,
    val currency: String,
    val dateEpochDay: Long,
    val category: String?,
    val createdAt: Long,
)

/** One entry in the action history (§44). */
data class ActionHistoryEntry(
    val id: Long,
    val actionType: String,
    val screenshotId: Long,
    val title: String,
    val success: Boolean,
    val createdAt: Long,
)

/** A locally generated, actionable task (§48). */
data class LocalTask(
    val id: String,
    val title: String,
    val detail: String,
    val dueEpochMillis: Long?,
    val screenshotIds: List<Long>,
    val state: TaskState,
    val source: String,
)

enum class TaskState(val label: String) {
    SUGGESTED("Suggested"),
    SCHEDULED("Scheduled"),
    COMPLETED("Completed"),
    DISMISSED("Dismissed"),
}
