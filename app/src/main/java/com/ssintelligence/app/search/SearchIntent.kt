package com.ssintelligence.app.search

/**
 * What the user is asking for (§6).
 *
 * Intent is a *ranking* hint, not a query-routing switch: it never changes
 * which SQL rows are eligible, it only nudges ordering and which structured
 * fields are given extra weight. That keeps intent classification from becoming
 * a source of hard-to-debug "no results" behaviour (§6).
 */
enum class SearchIntent {
    /** "Pixel 9" — a plain term lookup. */
    SEARCH,

    /** "Find the screenshot where I saw …" — an explicit retrieval request. */
    FIND,

    /** "screenshots from September", "show duplicates" — a structural request. */
    FILTER,

    /** No text terms at all: list by structure alone. */
    BROWSE,
}
