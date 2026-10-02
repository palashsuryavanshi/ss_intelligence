package com.ssintelligence.app.security

import com.ssintelligence.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Centralized privacy settings (§8, §49, §50).
 *
 * All privacy defaults are configurable and default to privacy-first where
 * it does not break usability.
 */
class PrivacyManager(
    private val settingsRepository: SettingsRepository,
) {

    suspend fun isSearchHistoryEnabled(): Boolean = settingsRepository.isSearchHistoryEnabled()
    suspend fun setSearchHistoryEnabled(enabled: Boolean) = settingsRepository.setSearchHistoryEnabled(enabled)

    suspend fun isAssistantHistoryEnabled(): Boolean = settingsRepository.isAssistantHistoryEnabled()
    suspend fun setAssistantHistoryEnabled(enabled: Boolean) = settingsRepository.setAssistantHistoryEnabled(enabled)

    suspend fun isAnalyticsEnabled(): Boolean = settingsRepository.isAnalyticsEnabled()
    suspend fun setAnalyticsEnabled(enabled: Boolean) = settingsRepository.setAnalyticsEnabled(enabled)

    suspend fun isCrashReportingEnabled(): Boolean = settingsRepository.isCrashReportingEnabled()
    suspend fun setCrashReportingEnabled(enabled: Boolean) = settingsRepository.setCrashReportingEnabled(enabled)

    suspend fun isAppLockEnabled(): Boolean = settingsRepository.isAppLockEnabled()
    suspend fun setAppLockEnabled(enabled: Boolean) = settingsRepository.setAppLockEnabled(enabled)

    suspend fun isSensitiveContentProtectionEnabled(): Boolean = settingsRepository.isSensitiveContentProtectionEnabled()
    suspend fun setSensitiveContentProtectionEnabled(enabled: Boolean) = settingsRepository.setSensitiveContentProtectionEnabled(enabled)

    suspend fun isSecureWindowEnabled(): Boolean = settingsRepository.isSecureWindowEnabled()
    suspend fun setSecureWindowEnabled(enabled: Boolean) = settingsRepository.setSecureWindowEnabled(enabled)

    suspend fun isNotificationPrivacyEnabled(): Boolean = settingsRepository.isNotificationPrivacyEnabled()
    suspend fun setNotificationPrivacyEnabled(enabled: Boolean) = settingsRepository.setNotificationPrivacyEnabled(enabled)

    suspend fun isClipboardProtectionEnabled(): Boolean = settingsRepository.isClipboardProtectionEnabled()
    suspend fun setClipboardProtectionEnabled(enabled: Boolean) = settingsRepository.setClipboardProtectionEnabled(enabled)
}