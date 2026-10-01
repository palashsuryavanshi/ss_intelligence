package com.ssintelligence.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.IndexingScope
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.ThemeMode
import com.ssintelligence.app.domain.repository.ProcessingMode
import com.ssintelligence.app.domain.repository.SettingsRepository
import com.ssintelligence.app.domain.repository.StorageBreakdown
import com.ssintelligence.app.indexing.SemanticIndexWorker
import com.ssintelligence.app.indexing.VisualIndexWorker
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

    // -------------------------------------------------------------- semantic

    /**
     * Semantic search state (§50).
     *
     * The provider is built in, so there is no download and no install state
     * to track — only enabled/disabled and how much of the library is
     * embedded. Disabling leaves the deterministic engine untouched.
     */
    private val _semanticEnabled = MutableStateFlow(true)
    val semanticEnabled: StateFlow<Boolean> = _semanticEnabled

    private val _embeddedCount = MutableStateFlow(0)
    val embeddedCount: StateFlow<Int> = _embeddedCount

    /** Rows embedded vs rows that could be: the "coverage" behind the toggle. */
    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount

    val semanticRebuildProgress: StateFlow<Int> =
        SemanticIndexWorker.observeProgress(locator.toApplicationContext())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val semanticModelInfo get() = locator.semanticRepository.modelInfo

    init {
        refreshSemantic()
    }

    fun onSemanticEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            settings.setSemanticSearchEnabled(enabled)
            _semanticEnabled.value = enabled
        }
    }

    fun onBuildSemanticIndex() {
        SemanticIndexWorker.requestNow(locator.toApplicationContext())
    }

    fun onClearSemanticIndex() {
        viewModelScope.launch {
            locator.semanticRepository.clearSemanticIndex()
            refreshSemanticCounts()
        }
    }

    fun refreshSemantic() {
        viewModelScope.launch {
            _semanticEnabled.value = settings.isSemanticSearchEnabled()
            refreshSemanticCounts()
        }
    }

    private suspend fun refreshSemanticCounts() {
        _embeddedCount.value = locator.semanticRepository.embeddedCount()
        _completedCount.value =
            locator.screenshotRepository.countByStatus()[ProcessingStatus.COMPLETED.name] ?: 0
    }

    // ------------------------------------------------- Phase 4 intelligence

    private val _processingMode = MutableStateFlow(ProcessingMode.AUTOMATIC)
    val processingMode: StateFlow<ProcessingMode> = _processingMode

    private val _storage = MutableStateFlow<StorageBreakdown?>(null)
    val storage: StateFlow<StorageBreakdown?> = _storage

    val visualRebuildProgress: StateFlow<Int> =
        VisualIndexWorker.observeProgress(locator.toApplicationContext())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        refreshPhase4()
    }

    fun onProcessingModeChange(mode: ProcessingMode) {
        viewModelScope.launch {
            settings.setProcessingMode(mode)
            _processingMode.value = mode
        }
    }

    fun onBuildVisualIndex() {
        VisualIndexWorker.requestNow(locator.toApplicationContext())
    }

    /** Deletes image embeddings and visual rows; text search is untouched (§64). */
    fun onClearVisualIndex() {
        viewModelScope.launch {
            locator.database.visualDao().deleteAllVisuals()
            refreshStorage()
        }
    }

    /** Deletes automatic categories; user corrections survive (§64). */
    fun onClearAutoCategories() {
        viewModelScope.launch {
            locator.database.semanticDao().deleteAllAutoCategories()
        }
    }

    /** Deletes entities and relationships; screenshots and extraction stay (§64). */
    fun onClearGraph() {
        viewModelScope.launch {
            locator.graphRepository.clearGraph()
            refreshStorage()
        }
    }

    fun refreshPhase4() {
        viewModelScope.launch {
            _processingMode.value = settings.processingMode()
            refreshStorage()
        }
    }

    private suspend fun refreshStorage() {
        _storage.value = locator.screenshotRepository.storageBreakdown()
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
