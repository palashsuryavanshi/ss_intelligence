package com.ssintelligence.app.ui.search

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.IndexingProgress
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.domain.usecase.ObserveIndexingProgressUseCase
import com.ssintelligence.app.domain.usecase.ObserveRecentSearchesUseCase
import com.ssintelligence.app.domain.usecase.ObserveSearchSuggestionsUseCase
import com.ssintelligence.app.domain.usecase.RecordSearchUseCase
import com.ssintelligence.app.domain.usecase.SearchScreenshotsUseCase
import com.ssintelligence.app.search.ContentType
import com.ssintelligence.app.search.ManualFilters
import com.ssintelligence.app.search.RelaxationLevel
import com.ssintelligence.app.search.SearchQuery
import com.ssintelligence.app.search.SearchRequest
import com.ssintelligence.app.search.SearchResponse
import com.ssintelligence.app.search.SearchResult
import com.ssintelligence.app.search.SearchSuggestion
import com.ssintelligence.app.search.ScreenshotSearchEngine
import com.ssintelligence.app.search.SortMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Search screen state (§25, §47).
 *
 * Query text, filter chips, manual refinements and sort order are separate
 * inputs, so changing any of them re-runs the current query immediately.
 * Keystrokes are debounced so the FTS query runs once per pause rather than
 * once per character (§31).
 *
 * Privacy: nothing here logs, records or reports the query. History is written
 * only on submit, and only when the user opted in; the repository additionally
 * refuses anything that looks like it carries a one-time code (§36, §37).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val engine: ScreenshotSearchEngine,
    private val history: SearchHistoryRepository,
    indexingProgress: ObserveIndexingProgressUseCase,
) : ViewModel() {

    private val queryText = MutableStateFlow("")
    private val contentTypes = MutableStateFlow(emptySet<ContentType>())
    private val manualFilters = MutableStateFlow(ManualFilters())
    private val sortMode = MutableStateFlow(SortMode.RELEVANCE)

    /**
     * Search-by-image target (§7).
     *
     * Set from the image picker; cleared by typing (a sentence replaces the
     * picture) or explicitly. Combined with the text query when both are
     * present — that combination is the multimodal search.
     */
    private val visualQueryId = MutableStateFlow<Long?>(null)
    val visualQuery: StateFlow<Long?> = visualQueryId.asStateFlow()

    val query: StateFlow<String> = queryText.asStateFlow()
    val filters: StateFlow<Set<ContentType>> = contentTypes.asStateFlow()
    val manual: StateFlow<ManualFilters> = manualFilters.asStateFlow()
    val sort: StateFlow<SortMode> = sortMode.asStateFlow()

    val indexing: StateFlow<IndexingProgress> = indexingProgress()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            IndexingProgress(total = 0, processed = 0, failed = 0),
        )

    /** Recent searches; empty unless history is enabled. */
    val recentSearches: StateFlow<List<String>> = ObserveRecentSearchesUseCase(history)()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local autocomplete. Nothing is derived from outside this device. */
    val suggestions: StateFlow<List<SearchSuggestion>> = queryText
        .debounce { if (it.isBlank()) 0L else SUGGESTION_DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { prefix ->
            if (prefix.trim().length < MIN_SUGGESTION_CHARS) {
                flowOf(emptyList())
            } else {
                ObserveSearchSuggestionsUseCase(engine)(prefix)
                    // A failing suggestion lookup must never break the screen;
                    // autocomplete is a convenience, not a result.
                    .catch { emit(emptyList()) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val request = combine(
        queryText.debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }.distinctUntilChanged(),
        contentTypes,
        manualFilters,
        sortMode,
        visualQueryId,
    ) { text, types, manual, sort, visualId ->
        SearchRequest(
            query = text,
            contentTypes = types,
            manualFilters = manual,
            sortMode = sort,
            visualQueryId = visualId,
        )
    }

    val state: StateFlow<SearchUiState> = request
        .flatMapLatest { req -> runSearch(req) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle)

    private fun runSearch(req: SearchRequest) = flow<SearchUiState> {
        // A pinned image is input on its own. Found on the device: the pin row
        // rendered correctly while the screen stayed on the idle prompt, because
        // this guard only looked at the text box and short-circuited before the
        // engine ever saw the visual query.
        val hasInput = req.query.isNotBlank() ||
            req.visualQueryId != null ||
            !req.manualFilters.isEmpty ||
            req.contentTypes.isNotEmpty()
        if (!hasInput) {
            emit(SearchUiState.Idle)
            return@flow
        }
        emit(SearchUiState.Searching)
        emitAll(
            SearchScreenshotsUseCase(engine)(req).map { response ->
                if (response.results.isEmpty()) {
                    SearchUiState.NoResults(response)
                } else {
                    SearchUiState.Results(response)
                }
            },
        )
    }.catch { error -> emit(classify(error)) }

    // ------------------------------------------------------------------ input

    fun onQueryChange(value: String) {
        queryText.value = value
    }

    /**
     * Pins or unpins the search-by-image target.
     *
     * Text and image combine when both are present — that combination is the
     * multimodal search — so typing never clears the pin. The pin has its own
     * explicit remove action instead.
     */
    fun onVisualQueryChange(id: Long?) {
        visualQueryId.value = id
    }

    /** IME "Search": also records the query in local history, when enabled. */
    fun onSubmit() {
        val text = queryText.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch { RecordSearchUseCase(history)(text) }
    }

    fun onSuggestionSelected(suggestion: String) {
        queryText.value = suggestion
        onSubmit()
    }

    fun onFilterToggle(type: ContentType) {
        contentTypes.value = contentTypes.value.let { current ->
            if (type in current) current - type else current + type
        }
    }

    /** Replaces the whole chip selection, used by the filter sheet. */
    fun onContentTypesChange(types: Set<ContentType>) {
        contentTypes.value = types
    }

    fun onSortChange(value: SortMode) {
        sortMode.value = value
    }

    fun onManualFiltersChange(value: ManualFilters) {
        manualFilters.value = value
    }

    fun onClearFilters() {
        contentTypes.value = emptySet()
        manualFilters.value = ManualFilters()
        sortMode.value = SortMode.RELEVANCE
    }

    fun onClearQuery() {
        queryText.value = ""
    }

    /**
     * Separates "the index is unusable" from "this search failed".
     *
     * The two need different actions — rebuild the index versus try again — so
     * they are different states (§47). The database's own message is
     * deliberately not shown: it can contain SQL and file paths, and this
     * screen has no business displaying any of it.
     */
    private fun classify(error: Throwable): SearchUiState {
        val chain = generateSequence(error) { it.cause }.toList()
        val looksLikeDatabase = chain.any { it is SQLiteException } ||
            chain.any { cause ->
                val message = cause.message.orEmpty()
                message.contains("SQLite", ignoreCase = true) ||
                    message.contains("Room", ignoreCase = true) ||
                    message.contains("database", ignoreCase = true)
            }
        return if (looksLikeDatabase) {
            SearchUiState.DatabaseUnavailable(
                "The local index could not be read. Rebuild it from Settings, or scan again.",
            )
        } else {
            SearchUiState.Error("Search could not be completed. Nothing left this device.")
        }
    }

    class Factory(
        private val locator: ServiceLocator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(
            engine = locator.searchEngine,
            history = locator.searchHistoryRepository,
            indexingProgress = ObserveIndexingProgressUseCase(locator.screenshotRepository),
        ) as T
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val SUGGESTION_DEBOUNCE_MS = 120L
        const val MIN_SUGGESTION_CHARS = 2
    }
}

/**
 * Explicit UI states (§47).
 *
 * A sealed hierarchy rather than a bag of booleans, because the screen must
 * never be able to show "no results" while still searching, and a search must
 * never render as a blank screen.
 */
sealed interface SearchUiState {

    /** Nothing requested yet. */
    data object Idle : SearchUiState

    data object Searching : SearchUiState

    data class Results(val response: SearchResponse) : SearchUiState {
        val results: List<SearchResult> get() = response.results
        val parsed: SearchQuery get() = response.query

        /** True when a constraint had to be dropped to produce these results. */
        val relaxed: Boolean
            get() = response.results.isNotEmpty() && response.relaxation != RelaxationLevel.EXACT
    }

    /** Nothing matched. [response] still carries what was understood. */
    data class NoResults(val response: SearchResponse) : SearchUiState {
        val parsed: SearchQuery get() = response.query
    }

    /** A query that failed for some other reason. */
    data class Error(val message: String) : SearchUiState

    /**
     * The local index could not be opened or read. Distinct from [Error]
     * because the fix is different: rebuild the index, not retry.
     */
    data class DatabaseUnavailable(val message: String) : SearchUiState

    /** Only populated when the state is [NoResults]: the parsed query. */
    val understoodAs: SearchQuery?
        get() = when (this) {
            is Results -> parsed
            is NoResults -> parsed
            else -> null
        }
}
