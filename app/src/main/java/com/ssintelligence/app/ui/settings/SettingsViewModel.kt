package com.ssintelligence.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.IndexingScope
import com.ssintelligence.app.domain.model.ThemeMode
import com.ssintelligence.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val scope: IndexingScope = IndexingScope.SCREENSHOTS_ONLY,
    val indexedCount: Int = 0,
    val isIndexing: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val locator: ServiceLocator,
) : ViewModel() {

    /** Database size is read on demand rather than observed: it changes rarely. */
    private val _databaseSizeBytes = MutableStateFlow(0L)
    val databaseSizeBytes: StateFlow<Long> = _databaseSizeBytes

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.observeTheme(),
        settings.observeScope(),
        locator.screenshotRepository.observeStats(),
        locator.indexingScheduler.observeWorkState(),
    ) { theme, scope, stats, workState ->
        SettingsUiState(
            theme = theme,
            scope = scope,
            indexedCount = stats.total,
            isIndexing = workState == androidx.work.WorkInfo.State.RUNNING ||
                workState == androidx.work.WorkInfo.State.ENQUEUED,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshDatabaseSize()
    }

    fun onThemeChange(mode: ThemeMode) {
        viewModelScope.launch { settings.setTheme(mode) }
    }

    fun onScopeChange(scope: IndexingScope) {
        viewModelScope.launch { settings.setScope(scope) }
    }

    fun onScanNow() {
        locator.indexingScheduler.requestIndexing()
    }

    fun onStopIndexing() {
        locator.indexingScheduler.cancel()
    }

    /** Re-reads every screenshot: OCR and extraction are computed again. */
    fun onRebuildIndex() {
        viewModelScope.launch {
            locator.screenshotRepository.requeueAllForReprocessing()
            locator.indexingScheduler.requestProcessingOnly()
            refreshDatabaseSize()
        }
    }

    /** Removes the app's local index only; the user's screenshots are untouched. */
    fun onClearIndex() {
        viewModelScope.launch {
            locator.indexingScheduler.cancel()
            locator.screenshotRepository.clearIndex()
            refreshDatabaseSize()
        }
    }

    fun refreshDatabaseSize() {
        viewModelScope.launch {
            _databaseSizeBytes.value = locator.screenshotRepository.databaseSizeBytes()
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            settings = locator.settingsRepository,
            locator = locator,
        ) as T
    }
}
