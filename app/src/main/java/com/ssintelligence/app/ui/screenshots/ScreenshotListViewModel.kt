package com.ssintelligence.app.ui.screenshots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.SearchFilter
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.search.ScreenshotSearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Browse-all screen state with an optional structural filter (§23, §26). */
class ScreenshotListViewModel(
    engine: ScreenshotSearchEngine,
) : ViewModel() {

    private val filterState = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = filterState.asStateFlow()

    /**
     * The browse list reuses the search engine with an empty query, so browse
     * and search share one code path and one ranking implementation.
     */
    val screenshots: StateFlow<List<Screenshot>> = filterState
        .flatMapLatest { engine.search("", it, limit = PAGE_LIMIT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onFilterChange(value: SearchFilter) {
        filterState.value = value
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ScreenshotListViewModel(locator.searchEngine) as T
    }

    private companion object {
        /**
         * Bounded page size: the list renders lazily, so a hard cap keeps the
         * first query cheap while still covering a realistic library (§31).
         * Keyset pagination in the DAO is the path for unbounded scrolling.
         */
        const val PAGE_LIMIT = 300
    }
}
