package com.ssintelligence.app.automation

import com.ssintelligence.app.actions.ActionRepository
import com.ssintelligence.app.actions.ActionType
import com.ssintelligence.app.actions.ExpenseRecord
import com.ssintelligence.app.actions.LocalReminder
import com.ssintelligence.app.actions.ActionHistoryEntry
import com.ssintelligence.app.actions.StoredAutomationExecution
import com.ssintelligence.app.actions.StoredAutomationRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rule evaluation, loop protection, encoding, and safety boundaries. */
class AutomationEngineTest {

    private class FakeActionRepository : ActionRepository {
        private val rules = MutableStateFlow(emptyList<StoredAutomationRule>())
        private var executed = mutableSetOf<Pair<Long, Long>>()
        var recorded = mutableListOf<Triple<Long, Long, String>>()
        private var nextId = 1L

        fun addRule(rule: StoredAutomationRule) {
            rules.value = rules.value + rule
        }

        override suspend fun createReminder(title: String, dueEpochMillis: Long?, screenshotId: Long): Long = 1L
        override fun observeReminders(): Flow<List<LocalReminder>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun deleteReminder(id: Long) = Unit

        override suspend fun saveExpense(
            screenshotId: Long, merchant: String?, amount: Double,
            currency: String, dateEpochDay: Long, category: String?,
        ): Long = 1L

        override fun observeExpenses(): Flow<List<ExpenseRecord>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun totalForCurrency(currency: String): Double = 0.0
        override suspend fun deleteExpense(id: Long) = Unit

        override suspend fun recordAction(actionType: String, screenshotId: Long, title: String, success: Boolean) = Unit
        override fun observeHistory(limit: Int): Flow<List<ActionHistoryEntry>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun clearHistory() = Unit

        override suspend fun createRule(
            name: String, triggerType: String, conditionJson: String,
            actionType: String, actionParamsJson: String,
        ): Long {
            val id = nextId++
            addRule(StoredAutomationRule(id, name, triggerType, conditionJson, actionType, actionParamsJson, true, 0L))
            return id
        }

        override fun observeRules(): Flow<List<StoredAutomationRule>> = rules.asStateFlow()
        override suspend fun setRuleEnabled(id: Long, enabled: Boolean) = Unit
        override suspend fun deleteRule(id: Long) = Unit

        override fun observeExecutions(limit: Int): Flow<List<StoredAutomationExecution>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun hasExecuted(ruleId: Long, screenshotId: Long): Boolean =
            (ruleId to screenshotId) in executed

        override suspend fun recordExecution(ruleId: Long, screenshotId: Long, actionType: String, result: String) {
            executed += ruleId to screenshotId
            recorded += Triple(ruleId, screenshotId, result)
        }

        override suspend fun clearExecutions() = Unit
        override suspend fun clearAll() = Unit
    }

    private fun rule(
        name: String = "Receipt Organizer",
        trigger: RuleTrigger = RuleTrigger.RECEIPT_DETECTED,
        conditions: List<RuleCondition> = emptyList(),
        action: RuleActionType = RuleActionType.ADD_TO_COLLECTION,
        params: Map<String, String> = mapOf("collection" to "Expenses"),
    ) = StoredAutomationRule(
        id = 1L, name = name, triggerType = trigger.name,
        conditionJson = AutomationEngine(FakeActionRepository()).encodeConditions(conditions),
        actionType = action.name,
        actionParamsJson = AutomationEngine(FakeActionRepository()).encodeParams(params),
        enabled = true, createdAt = 0L,
    )

    @Test
    fun `a matching rule fires once`() = runBlocking {
        val repo = FakeActionRepository()
        val engine = AutomationEngine(repo)
        repo.addRule(rule())
        val executions = engine.evaluate(42L, RuleTrigger.RECEIPT_DETECTED, emptyMap())
        assertEquals(1, executions.size)
        // No ScreenshotRepository wired in the test, so the engine reports
        // honestly what it would do rather than pretending it did it.
        assertEquals("Would add to Expenses", executions.single().result)
        assertTrue(repo.hasExecuted(1L, 42L))
    }

    @Test
    fun `a rule never fires twice for the same screenshot`() = runBlocking {
        val repo = FakeActionRepository()
        val engine = AutomationEngine(repo)
        repo.addRule(rule())
        engine.evaluate(42L, RuleTrigger.RECEIPT_DETECTED, emptyMap())
        val second = engine.evaluate(42L, RuleTrigger.RECEIPT_DETECTED, emptyMap())
        assertTrue(second.isEmpty())
        assertEquals(1, repo.recorded.size)
    }

    @Test
    fun `a failing condition blocks the rule`() = runBlocking {
        val repo = FakeActionRepository()
        val engine = AutomationEngine(repo)
        repo.addRule(rule(conditions = listOf(RuleCondition("merchant", "==", "Example Store"))))
        val executions = engine.evaluate(42L, RuleTrigger.RECEIPT_DETECTED, mapOf("merchant" to "Other"))
        assertTrue(executions.isEmpty())
    }

    @Test
    fun `a passing condition allows the rule`() = runBlocking {
        val repo = FakeActionRepository()
        val engine = AutomationEngine(repo)
        repo.addRule(rule(conditions = listOf(RuleCondition("merchant", "contains", "example"))))
        val executions = engine.evaluate(42L, RuleTrigger.RECEIPT_DETECTED, mapOf("merchant" to "Example Store"))
        assertEquals(1, executions.size)
    }

    @Test
    fun `a disabled rule never fires`() = runBlocking {
        val repo = FakeActionRepository()
        val engine = AutomationEngine(repo)
        repo.addRule(rule().copy(enabled = false))
        val executions = engine.evaluate(42L, RuleTrigger.RECEIPT_DETECTED, emptyMap())
        assertTrue(executions.isEmpty())
    }

    @Test
    fun `a wrong trigger never fires`() = runBlocking {
        val repo = FakeActionRepository()
        val engine = AutomationEngine(repo)
        repo.addRule(rule(trigger = RuleTrigger.TICKET_DETECTED))
        val executions = engine.evaluate(42L, RuleTrigger.RECEIPT_DETECTED, emptyMap())
        assertTrue(executions.isEmpty())
    }

    @Test
    fun `conditions round trip through encoding`() {
        val engine = AutomationEngine(FakeActionRepository())
        val conditions = listOf(
            RuleCondition("merchant", "==", "Example Store"),
            RuleCondition("amount", "!=", "0"),
        )
        assertEquals(conditions, engine.parseConditions(engine.encodeConditions(conditions)))
    }

    @Test
    fun `params round trip through encoding`() {
        val engine = AutomationEngine(FakeActionRepository())
        val params = mapOf("collection" to "Travel", "tag" to "trip")
        assertEquals(params, engine.parseParams(engine.encodeParams(params)))
    }

    @Test
    fun `calendar events are not automatic`() {
        assertFalse(AutomationSafety.allowedInAutomation(RuleActionType.CREATE_CALENDAR_EVENT))
        assertFalse(AutomationSafety.allowedInAutomation(RuleActionType.SHOW_NOTIFICATION))
    }

    @Test
    fun `collections tags and expenses are automatic`() {
        assertTrue(AutomationSafety.allowedInAutomation(RuleActionType.ADD_TO_COLLECTION))
        assertTrue(AutomationSafety.allowedInAutomation(RuleActionType.ADD_TAG))
        assertTrue(AutomationSafety.allowedInAutomation(RuleActionType.SAVE_EXPENSE))
        assertTrue(AutomationSafety.allowedInAutomation(RuleActionType.ARCHIVE))
    }

    @Test
    fun `consequential actions require confirmation`() {
        assertTrue(AutomationSafety.requiresConfirmation(ActionType.CALL_PHONE))
        assertTrue(AutomationSafety.requiresConfirmation(ActionType.SHARE))
        assertTrue(AutomationSafety.requiresConfirmation(ActionType.SAVE_EXPENSE))
        assertFalse(AutomationSafety.requiresConfirmation(ActionType.SEARCH_RELATED))
    }
}
