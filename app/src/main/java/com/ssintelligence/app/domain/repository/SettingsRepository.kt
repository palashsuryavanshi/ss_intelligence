package com.ssintelligence.app.domain.repository

import com.ssintelligence.app.domain.model.IndexingScope
import com.ssintelligence.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeTheme(): Flow<ThemeMode>
    suspend fun setTheme(mode: ThemeMode)

    fun observeScope(): Flow<IndexingScope>
    suspend fun setScope(scope: IndexingScope)

    fun observeOnboardingDone(): Flow<Boolean>
    suspend fun setOnboardingDone(done: Boolean)

    /**
     * Whether search queries may be stored locally (§36, §37).
     *
     * Defaults to off. The value is intentionally not observable as a Flow:
     * nothing in the app reacts to it changing except the recording path
     * itself, and a one-shot read keeps the write path from having to collect.
     */
    suspend fun isSearchHistoryEnabled(): Boolean
    suspend fun setSearchHistoryEnabled(enabled: Boolean)
}
