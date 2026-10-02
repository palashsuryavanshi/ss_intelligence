package com.ssintelligence.app.actions

import com.ssintelligence.app.data.database.ActionDao
import com.ssintelligence.app.data.database.ActionHistoryEntity
import com.ssintelligence.app.data.database.AutomationExecutionEntity
import com.ssintelligence.app.data.database.AutomationRuleEntity
import com.ssintelligence.app.data.database.ExpenseRecordEntity
import com.ssintelligence.app.data.database.LocalReminderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The action and automation repository (§63).
 *
 * Stores reminders, expenses, action history, automation rules and their
 * executions. Everything references screenshots by id; nothing duplicates image
 * data. Reminders and expenses are local-only and never become notifications
 * without permission.
 */
interface ActionRepository {

    // --------------- reminders

    suspend fun createReminder(title: String, dueEpochMillis: Long?, screenshotId: Long): Long
    fun observeReminders(): Flow<List<LocalReminder>>
    suspend fun deleteReminder(id: Long)

    // --------------- expenses

    suspend fun saveExpense(
        screenshotId: Long,
        merchant: String?,
        amount: Double,
        currency: String,
        dateEpochDay: Long,
        category: String?,
    ): Long
    fun observeExpenses(): Flow<List<ExpenseRecord>>
    suspend fun totalForCurrency(currency: String): Double
    suspend fun deleteExpense(id: Long)

    // --------------- action history

    suspend fun recordAction(actionType: String, screenshotId: Long, title: String, success: Boolean)
    fun observeHistory(limit: Int): Flow<List<ActionHistoryEntry>>
    suspend fun clearHistory()

    // --------------- automation rules

    suspend fun createRule(
        name: String,
        triggerType: String,
        conditionJson: String,
        actionType: String,
        actionParamsJson: String,
    ): Long
    fun observeRules(): Flow<List<StoredAutomationRule>>
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)
    suspend fun deleteRule(id: Long)

    // --------------- automation executions

    fun observeExecutions(limit: Int): Flow<List<StoredAutomationExecution>>
    suspend fun hasExecuted(ruleId: Long, screenshotId: Long): Boolean
    suspend fun recordExecution(ruleId: Long, screenshotId: Long, actionType: String, result: String)
    suspend fun clearExecutions()

    // --------------- clear all

    suspend fun clearAll()
}

/** A stored automation rule, as rows carry it. */
data class StoredAutomationRule(
    val id: Long,
    val name: String,
    val triggerType: String,
    val conditionJson: String,
    val actionType: String,
    val actionParamsJson: String,
    val enabled: Boolean,
    val createdAt: Long,
)

/** One recorded rule execution. */
data class StoredAutomationExecution(
    val id: Long,
    val ruleId: Long,
    val screenshotId: Long,
    val actionType: String,
    val result: String,
    val createdAt: Long,
)

class ActionRepositoryImpl(
    private val dao: ActionDao,
) : ActionRepository {

    override suspend fun createReminder(title: String, dueEpochMillis: Long?, screenshotId: Long): Long =
        dao.insertReminder(
            LocalReminderEntity(
                title = title,
                dueEpochMillis = dueEpochMillis,
                screenshotId = screenshotId,
                createdAt = System.currentTimeMillis(),
            ),
        )

    override fun observeReminders(): Flow<List<LocalReminder>> =
        dao.observeReminders().map { rows -> rows.map { LocalReminder(it.id, it.title, it.dueEpochMillis, it.screenshotId, it.createdAt) } }

    override suspend fun deleteReminder(id: Long) {
        dao.deleteReminder(id)
    }

    override suspend fun saveExpense(
        screenshotId: Long,
        merchant: String?,
        amount: Double,
        currency: String,
        dateEpochDay: Long,
        category: String?,
    ): Long = dao.insertExpense(
        ExpenseRecordEntity(
            screenshotId = screenshotId,
            merchant = merchant,
            amount = amount,
            currency = currency,
            dateEpochDay = dateEpochDay,
            category = category,
            createdAt = System.currentTimeMillis(),
        ),
    )

    override fun observeExpenses(): Flow<List<ExpenseRecord>> =
        dao.observeExpenses().map { rows ->
            rows.map { ExpenseRecord(it.id, it.screenshotId, it.merchant, it.amount, it.currency, it.dateEpochDay, it.category, it.createdAt) }
        }

    override suspend fun totalForCurrency(currency: String): Double = dao.totalForCurrency(currency)

    override suspend fun deleteExpense(id: Long) {
        dao.deleteExpense(id)
    }

    override suspend fun recordAction(actionType: String, screenshotId: Long, title: String, success: Boolean) {
        dao.insertHistory(
            ActionHistoryEntity(
                actionType = actionType,
                screenshotId = screenshotId,
                title = title,
                success = success,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override fun observeHistory(limit: Int): Flow<List<ActionHistoryEntry>> =
        dao.observeHistory(limit).map { rows ->
            rows.map { ActionHistoryEntry(it.id, it.actionType, it.screenshotId, it.title, it.success, it.createdAt) }
        }

    override suspend fun clearHistory() {
        dao.clearHistory()
    }

    override suspend fun createRule(
        name: String,
        triggerType: String,
        conditionJson: String,
        actionType: String,
        actionParamsJson: String,
    ): Long = dao.insertRule(
        AutomationRuleEntity(
            name = name,
            triggerType = triggerType,
            conditionJson = conditionJson,
            actionType = actionType,
            actionParamsJson = actionParamsJson,
            enabled = true,
            createdAt = System.currentTimeMillis(),
        ),
    )

    override fun observeRules(): Flow<List<StoredAutomationRule>> =
        dao.observeRules().map { rows ->
            rows.map {
                StoredAutomationRule(
                    it.id, it.name, it.triggerType, it.conditionJson,
                    it.actionType, it.actionParamsJson, it.enabled, it.createdAt,
                )
            }
        }

    override suspend fun setRuleEnabled(id: Long, enabled: Boolean) {
        dao.setRuleEnabled(id, enabled)
    }

    override suspend fun deleteRule(id: Long) {
        dao.deleteRule(id)
    }

    override fun observeExecutions(limit: Int): Flow<List<StoredAutomationExecution>> =
        dao.observeExecutions(limit).map { rows ->
            rows.map {
                StoredAutomationExecution(
                    it.id, it.ruleId, it.screenshotId,
                    it.actionType, it.result, it.createdAt,
                )
            }
        }

    override suspend fun hasExecuted(ruleId: Long, screenshotId: Long): Boolean =
        dao.hasExecuted(ruleId, screenshotId)

    override suspend fun recordExecution(ruleId: Long, screenshotId: Long, actionType: String, result: String) {
        dao.insertExecution(
            AutomationExecutionEntity(
                ruleId = ruleId,
                screenshotId = screenshotId,
                actionType = actionType,
                result = result,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun clearExecutions() {
        dao.clearExecutions()
    }

    override suspend fun clearAll() {
        dao.clearReminders()
        dao.clearExpenses()
        dao.clearHistory()
        dao.clearRules()
        dao.clearExecutions()
    }
}
