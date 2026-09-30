package com.ssintelligence.app.ui.screenshots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.SearchFilter
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.search.ContentType
import com.ssintelligence.app.search.SearchRequest
import com.ssintelligence.app.search.ScreenshotSearchEngine
import com.ssintelligence.app.search.SortMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Browse-all screen state with an optional structural filter (§23, §26). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotListViewModel(
    engine: ScreenshotSearchEngine,
) : ViewModel() {

    private val filterState = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = filterState.asStateFlow()

    /**
     * The browser reuses the search engine with no query and a filter chip, so
     * browse and search share one retrieval path and one filter implementation.
     * With no filter and no text this is a plain recency-ordered page.
     */
    val screenshots: StateFlow<List<Screenshot>> = filterState
        .flatMapLatest { active ->
            engine.observe(
                SearchRequest(
                    query = "",
                    contentTypes = active.toContentTypes(),
                    sortMode = SortMode.NEWEST,
                    limit = PAGE_LIMIT,
                ),
            )
        }
        .map { it.results.map { result -> result.screenshot } }
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

/** Phase 1 browse chips map onto the Phase 2 content-type filter. */
internal fun SearchFilter.toContentTypes(): Set<ContentType> = when (this) {
    SearchFilter.ALL -> emptySet()
    SearchFilter.URLS -> setOf(ContentType.URLS)
    SearchFilter.PRICES -> setOf(ContentType.PRICES)
    SearchFilter.DATES -> setOf(ContentType.DATES)
    SearchFilter.PHONES -> setOf(ContentType.PHONES)
    SearchFilter.OTPS -> setOf(ContentType.OTPS)
    SearchFilter.DUPLICATES -> setOf(ContentType.DUPLICATES)
}
