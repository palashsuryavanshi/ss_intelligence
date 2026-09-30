package com.ssintelligence.app.search

import com.ssintelligence.app.domain.model.Screenshot
import kotlinx.coroutines.flow.Flow

/**
 * The screenshot search engine (§4).
 *
 * The interface is the seam the whole UI is written against, so the
 * implementation can evolve — structured search today, optional local semantic
 * fusion later — without a single caller changing:
 *
 * ```
 * User Query → Query Understanding → Structured Search → Rank → Results
 * ```
 *
 * The contract is a `suspend` call rather than a `Flow` because a search is an
 * event, not a subscription: it has a parse, a bounded retrieval and a ranking
 * pass, and the caller wants one answer. [observe] exists for the screen that
 * genuinely wants re-running results (re-indexing changes what is findable).
 */
interface ScreenshotSearchEngine {

    /** The §4 contract: a query string in, ranked results out. */
    suspend fun search(query: String): List<SearchResult>

    /**
     * Full form, including manual filters, sort order and the relaxation level
     * the engine had to fall back to.
     */
    suspend fun search(request: SearchRequest): SearchResponse

    /** Parses without searching — used to show "Searching for …" (§25). */
    suspend fun parse(query: String): SearchQuery

    /** Re-runs a search whenever the index changes, for live result counts. */
    fun observe(request: SearchRequest): Flow<SearchResponse>

    /**
     * Local autocomplete (§38). Every suggestion is derived from data already
     * on this device: recent searches, indexed hosts, and phrases mined from
     * OCR text. Keystrokes never leave the process.
     */
    fun suggestions(prefix: String): Flow<List<SearchSuggestion>>
}

/** Maps a ranked result to the row shape the UI lists. */
fun SearchResult.toScreenshot(): Screenshot = screenshot
