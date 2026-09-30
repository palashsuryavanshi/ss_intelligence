package com.ssintelligence.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.usecase.ObserveIndexingStatsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Home screen state (§22). Everything is derived from database counters so the
 * numbers remain correct after process death.
 */
class HomeViewModel(
    private val observeStats: ObserveIndexingStatsUseCase,
    private val locator: ServiceLocator,
) : ViewModel() {

    val uiState: StateFlow<ObserveIndexingStatsUseCase.UiState> = observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObserveIndexingStatsUseCase.UiState(
            stats = com.ssintelligence.app.domain.model.IndexingStats(0, 0, 0, 0, 0, 0, 0),
            isIndexing = false,
        ))

    fun onScanNow() {
        locator.indexingScheduler.requestIndexing()
    }

    fun onStopIndexing() {
        locator.indexingScheduler.cancel()
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
            observeStats = ObserveIndexingStatsUseCase(
                repository = locator.screenshotRepository,
                scheduler = locator.indexingScheduler,
            ),
            locator = locator,
        ) as T
    }
}
