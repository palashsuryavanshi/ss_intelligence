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
}
