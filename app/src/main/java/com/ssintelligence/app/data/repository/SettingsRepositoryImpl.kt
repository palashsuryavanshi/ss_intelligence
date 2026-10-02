package com.ssintelligence.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ssintelligence.app.domain.model.IndexingScope
import com.ssintelligence.app.domain.model.ThemeMode
import com.ssintelligence.app.domain.repository.ProcessingMode
import com.ssintelligence.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "ss_settings")

/**
 * DataStore-backed settings (§34). Holds only non-sensitive UI preferences —
 * no screenshot data, no derived metadata.
 */
class SettingsRepositoryImpl(
    context: Context,
) : SettingsRepository {

    private val store = context.applicationContext.settingsStore

    override fun observeTheme(): Flow<ThemeMode> = store.data.map { prefs ->
        prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    override suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    override fun observeScope(): Flow<IndexingScope> = store.data.map { prefs ->
        prefs[SCOPE]?.let { runCatching { IndexingScope.valueOf(it) }.getOrNull() }
            ?: IndexingScope.SCREENSHOTS_ONLY
    }

    override suspend fun setScope(scope: IndexingScope) {
        store.edit { it[SCOPE] = scope.name }
    }

    override fun observeOnboardingDone(): Flow<Boolean> = store.data.map { prefs ->
        prefs[ONBOARDING_DONE] ?: false
    }

    override suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[ONBOARDING_DONE] = done }
    }

    override suspend fun isSearchHistoryEnabled(): Boolean {
        var enabled = false
        store.edit { prefs -> enabled = prefs[SEARCH_HISTORY_ENABLED] ?: false }
        return enabled
    }

    override suspend fun setSearchHistoryEnabled(enabled: Boolean) {
        store.edit { it[SEARCH_HISTORY_ENABLED] = enabled }
    }

    override suspend fun isSemanticSearchEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[SEMANTIC_SEARCH_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setSemanticSearchEnabled(enabled: Boolean) {
        store.edit { it[SEMANTIC_SEARCH_ENABLED] = enabled }
    }

    override suspend fun processingMode(): ProcessingMode {
        var mode = ProcessingMode.AUTOMATIC
        store.edit { prefs ->
            mode = prefs[PROCESSING_MODE]?.let { runCatching { ProcessingMode.valueOf(it) }.getOrNull() }
                ?: ProcessingMode.AUTOMATIC
        }
        return mode
    }

    override suspend fun setProcessingMode(mode: ProcessingMode) {
        store.edit { it[PROCESSING_MODE] = mode.name }
    }

    override suspend fun isAutomationEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[AUTOMATION_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setAutomationEnabled(enabled: Boolean) {
        store.edit { it[AUTOMATION_ENABLED] = enabled }
    }

    override suspend fun areContextActionsEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[CONTEXT_ACTIONS_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setContextActionsEnabled(enabled: Boolean) {
        store.edit { it[CONTEXT_ACTIONS_ENABLED] = enabled }
    }

    override suspend fun isExpenseExtractionEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[EXPENSE_EXTRACTION_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setExpenseExtractionEnabled(enabled: Boolean) {
        store.edit { it[EXPENSE_EXTRACTION_ENABLED] = enabled }
    }

    // ------------------------------------------------- Phase 8 privacy & security

    override suspend fun isAppLockEnabled(): Boolean {
        var enabled = false
        store.edit { prefs -> enabled = prefs[APP_LOCK_ENABLED] ?: false }
        return enabled
    }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        store.edit { it[APP_LOCK_ENABLED] = enabled }
    }

    override suspend fun isSensitiveContentProtectionEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[SENSITIVE_CONTENT_PROTECTION_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setSensitiveContentProtectionEnabled(enabled: Boolean) {
        store.edit { it[SENSITIVE_CONTENT_PROTECTION_ENABLED] = enabled }
    }

    override suspend fun isSecureWindowEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[SECURE_WINDOW_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setSecureWindowEnabled(enabled: Boolean) {
        store.edit { it[SECURE_WINDOW_ENABLED] = enabled }
    }

    override suspend fun isNotificationPrivacyEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[NOTIFICATION_PRIVACY_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setNotificationPrivacyEnabled(enabled: Boolean) {
        store.edit { it[NOTIFICATION_PRIVACY_ENABLED] = enabled }
    }

    override suspend fun isClipboardProtectionEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[CLIPBOARD_PROTECTION_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setClipboardProtectionEnabled(enabled: Boolean) {
        store.edit { it[CLIPBOARD_PROTECTION_ENABLED] = enabled }
    }

    override suspend fun isAssistantHistoryEnabled(): Boolean {
        var enabled = true
        store.edit { prefs -> enabled = prefs[ASSISTANT_HISTORY_ENABLED] ?: true }
        return enabled
    }

    override suspend fun setAssistantHistoryEnabled(enabled: Boolean) {
        store.edit { it[ASSISTANT_HISTORY_ENABLED] = enabled }
    }

    override suspend fun isAnalyticsEnabled(): Boolean {
        var enabled = false
        store.edit { prefs -> enabled = prefs[ANALYTICS_ENABLED] ?: false }
        return enabled
    }

    override suspend fun setAnalyticsEnabled(enabled: Boolean) {
        store.edit { it[ANALYTICS_ENABLED] = enabled }
    }

    override suspend fun isCrashReportingEnabled(): Boolean {
        var enabled = false
        store.edit { prefs -> enabled = prefs[CRASH_REPORTING_ENABLED] ?: false }
        return enabled
    }

    override suspend fun setCrashReportingEnabled(enabled: Boolean) {
        store.edit { it[CRASH_REPORTING_ENABLED] = enabled }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val SCOPE = stringPreferencesKey("indexing_scope")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
        val SEARCH_HISTORY_ENABLED = booleanPreferencesKey("search_history_enabled")
        val SEMANTIC_SEARCH_ENABLED = booleanPreferencesKey("semantic_search_enabled")
        val PROCESSING_MODE = stringPreferencesKey("processing_mode")
        val AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")
        val CONTEXT_ACTIONS_ENABLED = booleanPreferencesKey("context_actions_enabled")
        val EXPENSE_EXTRACTION_ENABLED = booleanPreferencesKey("expense_extraction_enabled")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val SENSITIVE_CONTENT_PROTECTION_ENABLED = booleanPreferencesKey("sensitive_content_protection_enabled")
        val SECURE_WINDOW_ENABLED = booleanPreferencesKey("secure_window_enabled")
        val NOTIFICATION_PRIVACY_ENABLED = booleanPreferencesKey("notification_privacy_enabled")
        val CLIPBOARD_PROTECTION_ENABLED = booleanPreferencesKey("clipboard_protection_enabled")
        val ASSISTANT_HISTORY_ENABLED = booleanPreferencesKey("assistant_history_enabled")
        val ANALYTICS_ENABLED = booleanPreferencesKey("analytics_enabled")
        val CRASH_REPORTING_ENABLED = booleanPreferencesKey("crash_reporting_enabled")
    }
}
