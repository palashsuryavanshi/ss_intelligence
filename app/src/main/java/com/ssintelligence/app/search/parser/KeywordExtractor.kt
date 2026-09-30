package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.parser.PhraseRun

/**
 * Turns the words left over after structured parsing into FTS terms and
 * preserved phrases (§7, §8).
 *
 * The extractor never sees the whole query on its own: [QueryParser] hands it
 * the spans already claimed by the price, date, URL, phone and code parsers, so
 * "₹39,999" becomes a price filter and *not* also a text term.
 *
 * Phrase detection is positional rather than dictionary-based. Words that were
 * adjacent in the query and survived trimming stay adjacent, so "Pixel 9a"
 * yields one phrase, while "AirPods from Amazon" yields two terms. A
 * dictionary of product names would not generalize; adjacency does.
 */
class KeywordExtractor(
    private val tokenizer: QueryTokenizer = QueryTokenizer,
) {

    /**
     * @param query the raw query text
     * @param consumedSpans regions already claimed by structured parsers
     */
    fun extract(query: String, consumedSpans: List<QuerySpan> = emptyList()): PhraseRun {
        val tokens = tokenizer.tokenize(query)
        if (tokens.isEmpty()) return PhraseRun(emptyList(), emptyList())

        val keep = BooleanArray(tokens.size)

        // Trim filler from both ends first: everything in between is then one
        // contiguous run, which is what makes phrase detection meaningful.
        var first = 0
        while (first < tokens.size && isEndFiller(tokens[first].lower)) first++
        var last = tokens.size - 1
        while (last >= first && isEndFiller(tokens[last].lower)) last--
        if (first > last) return PhraseRun(emptyList(), emptyList())
        for (i in first..last) keep[i] = true

        // Drop pure function words from the interior, and anything a structured
        // parser already consumed.
        for (i in first..last) {
            val token = tokens[i]
            if (!keep[i]) continue
            if (token.lower in StopWords.internal) keep[i] = false
            if (consumedSpans.any { it.contains(token.start) }) keep[i] = false
        }

        val surviving = tokens.indices.filter { keep[it] }
        if (surviving.isEmpty()) return PhraseRun(emptyList(), emptyList())

        // Group tokens that are adjacent *in the original token sequence*. A
        // removed interior word (a function word) still leaves its neighbours
        // adjacent, so "saw Pixel 9a" still produces the phrase "Pixel 9a".
        val phrases = mutableListOf<String>()
        var index = 0
        while (index < surviving.size) {
            val runStart = surviving[index]
            var runEnd = index
            while (runEnd + 1 < surviving.size && surviving[runEnd + 1] == surviving[runEnd] + 1) {
                runEnd++
            }
            val run = surviving.subList(index, runEnd + 1)
            if (run.size >= 2) {
                phrases += run.joinToString(" ") { tokens[it].lower }
            }
            index = runEnd + 1
        }

        // Individual terms give recall; the phrases above give precision.
        val terms = buildList {
            for (i in surviving) {
                val token = tokens[i]
                val inPhrase = phrases.any { it.split(' ').contains(token.lower) }
                // A one-character token on its own ("9" in "Pixel 9" without a
                // sibling) is almost always noise; inside a phrase it matters.
                if (token.lower.length < 2 && !inPhrase) continue
                add(token.lower)
            }
        }.distinct().take(MAX_TERMS)

        val usefulPhrases = phrases.filter { it.isNotBlank() }.distinct().take(MAX_PHRASES)
        return PhraseRun(terms = terms, phrases = usefulPhrases)
    }

    private fun isEndFiller(lower: String): Boolean =
        lower in StopWords.leadingTrim || lower in StopWords.trailingTrim

    private companion object {
        /**
         * Bounds kept small on purpose: every extra term narrows the FTS query
         * and makes an exact AND match less likely, for very little ranking
         * benefit. Phrase terms are still counted by [com.ssintelligence.app.search.SearchQuery.ftsTerms].
         */
        const val MAX_TERMS = 12
        const val MAX_PHRASES = 4
    }
}
