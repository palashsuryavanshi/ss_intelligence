package com.ssintelligence.app.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 7 action and automation storage (§63).
 *
 * Reminders, expenses, action history, automation rules and their executions
 * all reference screenshots by id. Nothing here duplicates image data, and
 * clearing any of it never touches the screenshot index.
 */

@Entity(
    tableName = "local_reminders",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"])],
)
data class LocalReminderEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "due_epoch_millis") val dueEpochMillis: Long?,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "expense_records",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"]), Index(value = ["date_epoch_day"])],
)
data class ExpenseRecordEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "merchant") val merchant: String?,
    @ColumnInfo(name = "amount") val amount: Double,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long,
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "action_history",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"]), Index(value = ["created_at"])],
)
data class ActionHistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "success") val success: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "automation_rules",
    indices = [Index(value = ["enabled"])],
)
data class AutomationRuleEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "trigger_type") val triggerType: String,
    @ColumnInfo(name = "condition_json") val conditionJson: String,
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "action_params_json") val actionParamsJson: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "automation_executions",
    foreignKeys = [
        ForeignKey(
            entity = AutomationRuleEntity::class,
            parentColumns = ["id"], childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["rule_id"]), Index(value = ["screenshot_id"])],
)
data class AutomationExecutionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "result") val result: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface ActionDao {

    // --------------- reminders

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: LocalReminderEntity): Long

    @Query("SELECT * FROM local_reminders ORDER BY created_at DESC")
    fun observeReminders(): Flow<List<LocalReminderEntity>>

    @Query("DELETE FROM local_reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    // --------------- expenses

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseRecordEntity): Long

    @Query("SELECT * FROM expense_records ORDER BY date_epoch_day DESC, created_at DESC")
    fun observeExpenses(): Flow<List<ExpenseRecordEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expense_records WHERE currency = :currency")
    suspend fun totalForCurrency(currency: String): Double

    @Query("DELETE FROM expense_records WHERE id = :id")
    suspend fun deleteExpense(id: Long)

    // --------------- action history

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: ActionHistoryEntity): Long

    @Query("SELECT * FROM action_history ORDER BY created_at DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<ActionHistoryEntity>>

    @Query("DELETE FROM action_history")
    suspend fun clearHistory()

    // --------------- automation rules

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AutomationRuleEntity): Long

    @Query("SELECT * FROM automation_rules ORDER BY created_at DESC")
    fun observeRules(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun ruleById(id: Long): AutomationRuleEntity?

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    // --------------- automation executions

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecution(execution: AutomationExecutionEntity): Long

    @Query("SELECT * FROM automation_executions ORDER BY created_at DESC LIMIT :limit")
    fun observeExecutions(limit: Int): Flow<List<AutomationExecutionEntity>>

    /** Prevents the same rule firing twice for the same screenshot (§68). */
    @Query("SELECT COUNT(*) FROM automation_executions WHERE rule_id = :ruleId AND screenshot_id = :screenshotId")
    suspend fun hasExecuted(ruleId: Long, screenshotId: Long): Boolean

    @Query("DELETE FROM automation_executions")
    suspend fun clearExecutions()

    // --------------- clear all

    @Query("DELETE FROM local_reminders")
    suspend fun clearReminders()

    @Query("DELETE FROM expense_records")
    suspend fun clearExpenses()

    @Query("DELETE FROM automation_rules")
    suspend fun clearRules()
}
