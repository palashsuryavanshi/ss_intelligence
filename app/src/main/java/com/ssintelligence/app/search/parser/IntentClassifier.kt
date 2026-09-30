package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.SearchIntent
import com.ssintelligence.app.search.SearchQuery

/**
 * Classifies what the user is asking for (§6).
 *
 * Intent never decides which rows are eligible — it is recorded so ranking can
 * weight a "find" request slightly differently from a plain keyword lookup,
 * and so the UI can describe the query in the user's own terms. Keeping it out
 * of the query-routing path means a misclassification can never turn a
 * successful search into an empty one.
 */
class IntentClassifier {

    private val requestVerbs = setOf(
        "find", "finds", "search", "searches", "locate", "spot", "get", "open", "view",
        "show", "shows", "showme", "where", "which", "what", "remember", "recall",
        "retrieve", "tell", "give", "pull", "display", "list",
    )

    fun classify(raw: String, query: SearchQuery): SearchIntent {
        val hasText = query.textTerms.isNotEmpty() || query.phrases.isNotEmpty()
        if (!hasText) {
            return if (query.isEmpty) SearchIntent.BROWSE else SearchIntent.FILTER
        }
        val opening = QueryTokenizer.tokenize(raw)
            .take(OPENING_TOKENS)
            .map { it.lower }
            .toSet()
        return if (opening.any { it in requestVerbs }) SearchIntent.FIND else SearchIntent.SEARCH
    }

    private companion object {
        /** Only the opening words count: "which screenshot had Pixel" is a find. */
        const val OPENING_TOKENS = 4
    }
}
