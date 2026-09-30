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

    /**
     * Search history state.
     *
     * `enabled` is read back after every write so the switch cannot drift out of
     * sync with what is actually stored, and `count` is the number of queries
     * currently on the device.
     */
    private val _searchHistoryEnabled = MutableStateFlow(false)
    val searchHistoryEnabled: StateFlow<Boolean> = _searchHistoryEnabled

    private val _searchHistoryCount = MutableStateFlow(0)
    val searchHistoryCount: StateFlow<Int> = _searchHistoryCount

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
        refreshSearchHistory()
    }

    /**
     * Opting out also deletes what was already stored.
     *
     * Leaving the switch off while old queries sit on disk would make "off"
     * mean nothing, which is the opposite of what the label promises.
     */
    fun onSearchHistoryEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            settings.setSearchHistoryEnabled(enabled)
            if (!enabled) locator.searchHistoryRepository.clear()
            _searchHistoryEnabled.value = enabled
            refreshSearchHistoryCount()
        }
    }

    fun onClearSearchHistory() {
        viewModelScope.launch {
            locator.searchHistoryRepository.clear()
            refreshSearchHistoryCount()
        }
    }

    fun refreshSearchHistory() {
        viewModelScope.launch {
            _searchHistoryEnabled.value = settings.isSearchHistoryEnabled()
            refreshSearchHistoryCount()
        }
    }

    private suspend fun refreshSearchHistoryCount() {
        _searchHistoryCount.value = locator.searchHistoryRepository.count()
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
