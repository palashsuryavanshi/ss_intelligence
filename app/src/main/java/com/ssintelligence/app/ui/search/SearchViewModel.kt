package com.ssintelligence.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.SearchFilter
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.search.ScreenshotSearchEngine
import com.ssintelligence.app.domain.usecase.SearchScreenshotsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Search screen state (§25, §26).
 *
 * Query text and the active filter are separate inputs, so switching filters
 * re-runs the current query immediately. Keystrokes are debounced so FTS runs
 * once per pause rather than once per character (§31).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    engine: ScreenshotSearchEngine,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filterState = MutableStateFlow(SearchFilter.ALL)

    val filter: StateFlow<SearchFilter> = filterState.asStateFlow()

    private val search = SearchScreenshotsUseCase(engine)

    val results: StateFlow<SearchUiState> =
        combine(
            query.debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }.distinctUntilChanged(),
            filterState,
        ) { text, activeFilter -> text to activeFilter }
            .flatMapLatest { (text, activeFilter) -> runSearch(text, activeFilter) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    private fun runSearch(text: String, activeFilter: SearchFilter): Flow<SearchUiState> = flow {
        emit(SearchUiState(query = text, isLoading = true))
        emitAll(
            search(text, activeFilter).map { list ->
                SearchUiState(query = text, isLoading = false, results = list)
            }
        )
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterChange(value: SearchFilter) {
        filterState.value = value
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(locator.searchEngine) as T
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
    }
}

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = true,
    val results: List<Screenshot> = emptyList(),
)
