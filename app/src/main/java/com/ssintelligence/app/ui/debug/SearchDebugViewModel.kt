package com.ssintelligence.app.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.search.SearchQuery
import com.ssintelligence.app.search.SearchRequest
import com.ssintelligence.app.search.SearchResponse
import com.ssintelligence.app.search.ScreenshotSearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backing state for the debug-only search inspector (§46).
 *
 * Runs the engine manually rather than as the screen does, so the parsed query
 * and the response are captured at the same instant and can be compared. It
 * writes nothing to search history: inspecting a query must not record it.
 */
class SearchDebugViewModel(
    private val engine: ScreenshotSearchEngine,
) : ViewModel() {

    data class State(
        val query: String = "",
        val parsed: SearchQuery? = null,
        val response: SearchResponse? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onQueryChange(value: String) {
        _state.value = _state.value.copy(query = value, error = null)
    }

    fun run() {
        val text = _state.value.query
        viewModelScope.launch {
            try {
                val parsed = engine.parse(text)
                val response = engine.search(SearchRequest(query = text))
                _state.value = _state.value.copy(
                    parsed = parsed,
                    response = response,
                    error = null,
                )
            } catch (error: Throwable) {
                // A debug tool should say what went wrong, but must not print a
                // query it was handed into a log.
                _state.value = _state.value.copy(
                    parsed = null,
                    response = null,
                    error = error.javaClass.simpleName +
                        (error.message?.let { ": $it" } ?: ""),
                )
            }
        }
    }

    class Factory(
        private val locator: ServiceLocator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchDebugViewModel(locator.searchEngine) as T
    }
}
