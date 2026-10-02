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

    // ------------------------------------------------- Phase 7 automation

    /** Master switch for all local automation rules (§60, §71). */
    suspend fun isAutomationEnabled(): Boolean
    suspend fun setAutomationEnabled(enabled: Boolean)

    /** Whether contextual action suggestions appear on detail pages. */
    suspend fun areContextActionsEnabled(): Boolean
    suspend fun setContextActionsEnabled(enabled: Boolean)

    /** Whether expense extraction is offered on receipts. */
    suspend fun isExpenseExtractionEnabled(): Boolean
    suspend fun setExpenseExtractionEnabled(enabled: Boolean)

    // ------------------------------------------------- Phase 8 privacy & security

    /** Whether app lock (biometric/device credential) is required on open. */
    suspend fun isAppLockEnabled(): Boolean
    suspend fun setAppLockEnabled(enabled: Boolean)

    /** Whether sensitive thumbnails are hidden until authenticated. */
    suspend fun isSensitiveContentProtectionEnabled(): Boolean
    suspend fun setSensitiveContentProtectionEnabled(enabled: Boolean)

    /** Whether app-switcher preview uses secure-window FLAG_SECURE. */
    suspend fun isSecureWindowEnabled(): Boolean
    suspend fun setSecureWindowEnabled(enabled: Boolean)

    /** Whether notifications redact sensitive content. */
    suspend fun isNotificationPrivacyEnabled(): Boolean
    suspend fun setNotificationPrivacyEnabled(enabled: Boolean)

    /** Whether clipboard auto-clears after timeout for sensitive copies. */
    suspend fun isClipboardProtectionEnabled(): Boolean
    suspend fun setClipboardProtectionEnabled(enabled: Boolean)

    suspend fun isAssistantHistoryEnabled(): Boolean
    suspend fun setAssistantHistoryEnabled(enabled: Boolean)

    /** Analytics is OFF by default and never collects screenshot content. */
    suspend fun isAnalyticsEnabled(): Boolean
    suspend fun setAnalyticsEnabled(enabled: Boolean)

    suspend fun isCrashReportingEnabled(): Boolean
    suspend fun setCrashReportingEnabled(enabled: Boolean)
}

/** Background processing policy for expensive intelligence jobs. */
enum class ProcessingMode(val label: String) {
    AUTOMATIC("Automatic"),
    CHARGING_ONLY("Charging only"),
    MANUAL("Manual"),
}
