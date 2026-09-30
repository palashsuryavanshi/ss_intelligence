package com.ssintelligence.app.search

import com.ssintelligence.app.domain.model.Screenshot

/**
 * One ranked search hit (§26, §27).
 *
 * [score] is an internal relevance number. It is deliberately **not** exposed in
 * the UI: there is no probability model behind it, so presenting it as "92%
 * match" would be a lie (§27). What is shown instead is [matches] — the plain
 * reasons the row was selected.
 */
data class SearchResult(
    val screenshot: Screenshot,
    val score: Int,
    val snippet: Snippet?,
    val matches: List<MatchReason>,
)

/** Why a screenshot was selected. Display text only, never a score. */
data class MatchReason(
    val label: String,
    val kind: MatchKind,
)

enum class MatchKind {
    PHRASE,
    TERM,
    PRICE,
    URL,
    PHONE,

    /**
     * A one-time code matched. The label is always the generic
     * "One-time code": the value itself is never rendered (§15).
     */
    OTP,
    DATE,
    FILENAME,
    DUPLICATE,
}

/**
 * Which relaxation step produced these results (§29).
 *
 * Surfaced so the UI can say *out loud* that a constraint was dropped. Silently
 * weakening a query would be a lie about what the user asked for.
 */
enum class RelaxationLevel(val userMessage: String?) {
    /** Everything the user specified was applied. */
    EXACT(null),

    /** The exact price found nothing; a ±tolerance band was tried instead. */
    PRICE_APPROXIMATE("No exact match. Showing prices close to what you asked for."),

    /** The price constraint was dropped; the text was kept. */
    TEXT_ONLY("No exact match. Showing screenshots matching your words."),

    /** Individual words were OR-ed instead of AND-ed. */
    ANY_TERM("No exact match. Showing screenshots matching any of your words."),

    /** Text was dropped entirely; only structural filters applied. */
    FILTERS_ONLY("No match for the words. Showing screenshots that match your filters only."),
}

/**
 * The complete outcome of one search (§47).
 *
 * [candidateCount] and [elapsedMillis] exist for the debug screen only (§46);
 * they are cheap counters, not sensitive data.
 */
data class SearchResponse(
    val query: SearchQuery,
    val results: List<SearchResult>,
    val relaxation: RelaxationLevel,
    val candidateCount: Int,
    val elapsedMillis: Long,
) {
    val isEmpty: Boolean get() = results.isEmpty()

    /** Whether the UI must explain that constraints were relaxed. */
    val wasRelaxed: Boolean get() = relaxation != RelaxationLevel.EXACT
}

/**
 * Everything the search screen needs for one run, including the raw text box
 * contents and the manually applied refinements (§30).
 */
data class SearchRequest(
    val query: String,
    val contentTypes: Set<ContentType> = emptySet(),
    val manualFilters: ManualFilters = ManualFilters(),
    val sortMode: SortMode = SortMode.RELEVANCE,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        /**
         * Hard cap on returned rows. Paging beyond this is a future step (§33);
         * the list is lazy so a few hundred rows is already more than fits on
         * screen.
         */
        const val DEFAULT_LIMIT = 100
    }
}

/**
 * User-applied refinements that the parser could not infer (§30).
 *
 * These are AND-ed with whatever the parser derived: a manual range narrows a
 * natural-language query rather than replacing it.
 */
data class ManualFilters(
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val currency: String? = null,
    val timeRange: TimeRange? = null,
    val domain: String? = null,
    val phone: String? = null,
    val hasOtp: Boolean? = null,
    val duplicatesOnly: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = minPrice == null && maxPrice == null && currency == null && timeRange == null &&
            domain == null && phone == null && hasOtp == null && duplicatesOnly == null
}

/**
 * One autocomplete entry (§38).
 *
 * Every suggestion is derived from data already on the device: recent searches
 * and things that appear in the local index. Nothing is generated or predicted.
 */
data class SearchSuggestion(
    val text: String,
    val source: SuggestionSource,
)

enum class SuggestionSource {
    /** Something the user searched for before, this session or historically. */
    RECENT,

    /** A host seen in an indexed screenshot. */
    DOMAIN,

    /** A phrase mined from OCR text in the local index. */
    OCR,
}
