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

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val SCOPE = stringPreferencesKey("indexing_scope")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
        val SEARCH_HISTORY_ENABLED = booleanPreferencesKey("search_history_enabled")
        val SEMANTIC_SEARCH_ENABLED = booleanPreferencesKey("semantic_search_enabled")
        val PROCESSING_MODE = stringPreferencesKey("processing_mode")
    }
}
