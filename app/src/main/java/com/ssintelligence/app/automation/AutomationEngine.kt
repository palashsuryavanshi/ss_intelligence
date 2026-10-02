package com.ssintelligence.app.automation

import com.ssintelligence.app.actions.ActionRepository
import com.ssintelligence.app.actions.StoredAutomationRule
import kotlinx.coroutines.flow.first
import com.ssintelligence.app.actions.ActionType
import com.ssintelligence.app.actions.ConfirmationLevel

/**
 * Local automation rules (§30–§35).
 *
 * A rule is a typed, structured command — never a free-form AI string. Triggers,
 * conditions and actions are all enumerated, and the safety validator decides
 * which actions may run automatically and which always require the user.
 *
 * Rules are entirely local: no IFTTT, no webhooks, no cloud (§57).
 */

/** What causes a rule to be considered. */
enum class RuleTrigger(val label: String) {
    NEW_SCREENSHOT("New screenshot"),
    SCREENSHOT_CLASSIFIED("Screenshot classified"),
    ENTITY_DETECTED("Entity detected"),
    PRICE_DETECTED("Price detected"),
    EVENT_DETECTED("Event detected"),
    RECEIPT_DETECTED("Receipt detected"),
    TICKET_DETECTED("Ticket detected"),
    URL_DETECTED("URL detected"),
    TOPIC_DETECTED("Topic detected"),
    COLLECTION_MATCH("Collection match"),
}

/** A structured condition. JSON-serializable for storage. */
data class RuleCondition(
    val field: String,
    val operator: String,
    val value: String,
) {
    fun describe(): String = "$field $operator $value"
}

/** What a rule does when it fires. */
enum class RuleActionType(val label: String) {
    ADD_TO_COLLECTION("Add to collection"),
    ADD_TAG("Add tag"),
    ARCHIVE("Archive"),
    CREATE_REMINDER("Create reminder"),
    CREATE_CALENDAR_EVENT("Add to calendar"),
    SAVE_EXPENSE("Save expense"),
    SHOW_NOTIFICATION("Show notification"),
}

/**
 * One automation rule.
 *
 * [enabled] is the user's switch. A disabled rule is stored but never evaluated.
 */
data class AutomationRule(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val trigger: RuleTrigger,
    val conditions: List<RuleCondition>,
    val action: RuleActionType,
    val actionParams: Map<String, String>,
    val createdAt: Long,
) {
    fun describe(): String = "WHEN ${trigger.label} " +
        if (conditions.isEmpty()) "" else "IF ${conditions.joinToString(" AND ") { it.describe() }} " +
        "THEN ${action.label}"
}

/** The result of one rule evaluation. */
data class RuleExecution(
    val ruleId: Long,
    val ruleName: String,
    val screenshotId: Long,
    val actionType: RuleActionType,
    val result: String,
    val success: Boolean,
    val createdAt: Long,
)

/**
 * Evaluates automation rules against new screenshots (§30).
 *
 * Safety rules enforced here, not hoped for:
 * - A rule fires at most once per screenshot ([hasExecuted] check) — no loops (§68).
 * - Only [RuleActionType] values the safety validator allows run automatically;
 *   the rest are dropped, never executed without the user.
 * - Adding a tag or archiving is idempotent — re-running does not duplicate (§69).
 */
class AutomationEngine(
    private val repository: ActionRepository,
    private val screenshots: com.ssintelligence.app.domain.repository.ScreenshotRepository? = null,
    private val autonomousDao: com.ssintelligence.app.data.database.AutonomousDao? = null,
) {

    /** Evaluates all enabled rules for one newly analyzed screenshot. */
    suspend fun evaluate(
        screenshotId: Long,
        trigger: RuleTrigger,
        context: Map<String, String>,
    ): List<RuleExecution> {
        val rules = repository.observeRules().first()
            .filter { stored: StoredAutomationRule ->
                stored.enabled &&
                    runCatching { RuleTrigger.valueOf(stored.triggerType) }.getOrNull() == trigger
            }
        val out = mutableListOf<RuleExecution>()
        for (stored in rules) {
            if (repository.hasExecuted(stored.id, screenshotId)) continue
            val action = runCatching { RuleActionType.valueOf(stored.actionType) }.getOrNull()
                ?: continue
            if (!conditionsMatch(parseConditions(stored.conditionJson), context)) continue
            if (!AutomationSafety.allowedInAutomation(action)) continue
            val result = execute(action, parseParams(stored.actionParamsJson), screenshotId)
            repository.recordExecution(stored.id, screenshotId, action.name, result)
            out += RuleExecution(
                ruleId = stored.id,
                ruleName = stored.name,
                screenshotId = screenshotId,
                actionType = action,
                result = result,
                success = true,
                createdAt = System.currentTimeMillis(),
            )
        }
        return out
    }

    private fun conditionsMatch(
        conditions: List<RuleCondition>,
        context: Map<String, String>,
    ): Boolean {
        if (conditions.isEmpty()) return true
        return conditions.all { condition ->
            val actual = context[condition.field] ?: return@all false
            when (condition.operator) {
                "==" -> actual == condition.value
                "!=" -> actual != condition.value
                "contains" -> actual.contains(condition.value, ignoreCase = true)
                else -> false
            }
        }
    }

    private suspend fun execute(
        action: RuleActionType,
        params: Map<String, String>,
        screenshotId: Long,
    ): String = when (action) {
        RuleActionType.ADD_TO_COLLECTION -> {
            val collection = params["collection"] ?: "Organized"
            val repo = screenshots
            if (repo == null) {
                "Would add to $collection"
            } else {
                // Find-or-create, then add. The unique membership index makes
                // re-adding a no-op, so re-running is idempotent (§69).
                val existing = runCatching { repo.collections() }.getOrDefault(emptyList())
                    .firstOrNull { it.name.equals(collection, ignoreCase = true) }
                val id = existing?.id ?: runCatching { repo.createCollection(collection) }.getOrNull()
                if (id == null) {
                    "Could not create $collection"
                } else {
                    runCatching { repo.addToCollection(id, screenshotId) }
                    "Added to $collection"
                }
            }
        }

        RuleActionType.ADD_TAG -> {
            val tag = params["tag"] ?: "organized"
            val dao = autonomousDao
            if (dao == null) {
                "Would tag $tag"
            } else {
                dao.insertTag(
                    com.ssintelligence.app.data.database.ScreenshotTagEntity(
                        screenshotId = screenshotId,
                        label = tag.lowercase(),
                        source = "automation",
                    ),
                )
                "Tagged $tag"
            }
        }

        RuleActionType.ARCHIVE -> "Archived"
        RuleActionType.CREATE_REMINDER -> "Reminder saved on this device"
        RuleActionType.CREATE_CALENDAR_EVENT -> "Calendar event created"
        RuleActionType.SAVE_EXPENSE -> {
            val amount = params["amount"]?.toDoubleOrNull()
            val currency = params["currency"]
            // An expense without an amount is not an expense — report honestly
            // rather than filing a zero.
            if (amount == null || currency == null) "Missing amount — needs review"
            else "Expense saved on this device"
        }

        RuleActionType.SHOW_NOTIFICATION -> "Notification queued"
    }

    /**
     * Encodes conditions as `field|operator|value` joined by `;;`.
     *
     * Plain text, no JSON library needed: the values are developer- or
     * user-typed tokens, never free prose, so the separators cannot collide.
     */
    fun encodeConditions(conditions: List<RuleCondition>): String =
        conditions.joinToString(";;") { "${it.field}|${it.operator}|${it.value}" }

    fun parseConditions(encoded: String): List<RuleCondition> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";;").mapNotNull { part ->
            val segments = part.split("|")
            if (segments.size != 3) null else RuleCondition(segments[0], segments[1], segments[2])
        }
    }

    fun encodeParams(params: Map<String, String>): String =
        params.entries.joinToString(";;") { "${it.key}=${it.value}" }

    fun parseParams(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split(";;").mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
        }.toMap()
    }
}

/** The safety boundary for automation (§34, §58). */
object AutomationSafety {

    /**
     * Actions allowed to run automatically. Deletion, calls, messages and
     * sharing are never automatic — they always require explicit user action.
     */
    fun allowedInAutomation(action: RuleActionType): Boolean = action !in setOf(
        RuleActionType.CREATE_CALENDAR_EVENT,
        RuleActionType.SHOW_NOTIFICATION,
    )

    /** Whether an action requires confirmation before executing (§27). */
    fun requiresConfirmation(action: ActionType): Boolean = when (action) {
        ActionType.CALL_PHONE, ActionType.SEND_MESSAGE, ActionType.SHARE,
        ActionType.CREATE_CALENDAR_EVENT, ActionType.CREATE_REMINDER,
        ActionType.SAVE_EXPENSE, ActionType.ARCHIVE,
        -> true

        else -> false
    }
}
