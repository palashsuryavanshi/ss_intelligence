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

    /**
     * Whether the semantic half of search may run (§50 Phase 3).
     *
     * Defaults to on: the provider is built in, needs no download, and costs
     * no privacy. Turning it off leaves the Phase 2 deterministic engine
     * exactly as it was.
     */
    suspend fun isSemanticSearchEnabled(): Boolean
    suspend fun setSemanticSearchEnabled(enabled: Boolean)

    /**
     * Background intelligence processing mode (§62).
     *
     * Automatic runs catch-up work whenever; Charging Only waits for power;
     * Manual means only explicit taps run the builders. Basic browsing and
     * search work identically in every mode — this governs expensive
     * background embedding only.
     */
    suspend fun processingMode(): ProcessingMode
    suspend fun setProcessingMode(mode: ProcessingMode)
}

/** Background processing policy for expensive intelligence jobs. */
enum class ProcessingMode(val label: String) {
    AUTOMATIC("Automatic"),
    CHARGING_ONLY("Charging only"),
    MANUAL("Manual"),
}
