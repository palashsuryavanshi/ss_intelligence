package com.ssintelligence.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.usecase.ObserveIndexingStatsUseCase
import com.ssintelligence.app.domain.usecase.ObserveSmartGroupsUseCase
import com.ssintelligence.app.semantic.SmartGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home screen state (§22). Everything is derived from database counters so the
 * numbers remain correct after process death.
 */
class HomeViewModel(
    private val observeStats: ObserveIndexingStatsUseCase,
    private val observeGroups: ObserveSmartGroupsUseCase,
    private val locator: ServiceLocator,
) : ViewModel() {

    val uiState: StateFlow<ObserveIndexingStatsUseCase.UiState> = observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObserveIndexingStatsUseCase.UiState(
            stats = com.ssintelligence.app.domain.model.IndexingStats(0, 0, 0, 0, 0, 0, 0),
            isIndexing = false,
        ))

    /**
     * Smart collections, loaded once per subscription rather than observed:
     * groups change only when the index or the categories change, and a
     * recompute per keystroke would be waste. Refreshed on demand.
     */
    private val _groups = MutableStateFlow(emptyList<SmartGroup>())
    val groups: StateFlow<List<SmartGroup>> = _groups.asStateFlow()

    /**
     * One quiet suggestion (§35): the most-referenced product entity, shown
     * only when it covers enough screenshots to be worth opening. Inside the
     * app, never a notification.
     */
    private val _suggestion = MutableStateFlow<TopEntitySuggestion?>(null)
    val suggestion: StateFlow<TopEntitySuggestion?> = _suggestion.asStateFlow()

    data class TopEntitySuggestion(
        val entityId: Long,
        val label: String,
        val count: Int,
    )

    init {
        refreshGroups()
        refreshSuggestion()
    }

    fun refreshGroups() {
        viewModelScope.launch {
            _groups.value = runCatching { observeGroups() }.getOrDefault(emptyList())
        }
    }

    fun refreshSuggestion() {
        viewModelScope.launch {
            val top = runCatching {
                locator.screenshotRepository.topEntities(
                    listOf(com.ssintelligence.app.graph.GraphEntityType.PRODUCT),
                    minCount = SUGGESTION_MIN_COUNT,
                    limit = 1,
                )
            }.getOrDefault(emptyList()).firstOrNull()
            _suggestion.value = top?.let {
                TopEntitySuggestion(it.entity.id, it.entity.displayName, it.screenshotCount)
            }
        }
    }

    fun onScanNow() {
        locator.indexingScheduler.requestIndexing()
    }

    fun onStopIndexing() {
        locator.indexingScheduler.cancel()
    }

    private companion object {
        /** A suggestion needs enough screenshots to be worth opening. */
        const val SUGGESTION_MIN_COUNT = 5
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
            observeStats = ObserveIndexingStatsUseCase(
                repository = locator.screenshotRepository,
                scheduler = locator.indexingScheduler,
            ),
            observeGroups = ObserveSmartGroupsUseCase(locator.screenshotRepository),
            locator = locator,
        ) as T
    }
}
