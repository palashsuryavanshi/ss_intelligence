package com.ssintelligence.app.search

/**
 * Builds FTS `MATCH` expressions from raw user input.
 *
 * Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM.
 * Prefers FTS MATCH over `LIKE '%query%'` (§25). All terms are AND-ed; each
 * term is quoted and prefix-matched so "pixel 9a" matches "Pixel 9a 5G" but
 * not "pixelated".
 */
object FtsQueryBuilder {
    private val trailingPunctuation = ".,;:!?\"'()[]{}".toSet()

    /** How the terms of a query are combined. */
    enum class Conjunction { AND, OR }

    /**
     * Returns an FTS4 MATCH expression, or null when the input has no
     * searchable tokens.
     *
     * Terms are combined with the *implicit* AND operator (plain whitespace),
     * not the explicit `AND` keyword. Room's `@Fts4` builds an FTS4 virtual
     * table, and SQLite's FTS4 parser rejects an explicit `AND` in this
     * configuration — it would make every multi-term query silently match
     * nothing. Whitespace-separated phrases are AND-ed by the engine, which is
     * verified by an instrumented test in ScreenshotDaoInstrumentedTest.
     *
     * Single-character tokens are kept: "Pixel 9" must not degrade into a search
     * for every Pixel. The keyword extractor is what drops a lone one-character
     * token, because that is where "meaningless" can be judged.
     */
    fun build(rawQuery: String): String? = buildFromTerms(tokenize(rawQuery))

    /**
     * Builds a MATCH expression from already-parsed terms.
     *
     * The Phase 2 engine uses this rather than [build], because the query has
     * been through [com.ssintelligence.app.search.parser.QueryParser] by then:
     * terms have had filler and structured values removed, so re-tokenizing
     * the raw string here would undo that work.
     *
     * @param or when true, terms are combined with `OR` instead of the implicit
     *   AND. This backs the [RelaxationLevel.ANY_TERM] fallback and the semantic
     *   prefilter. OR terms are matched *without* the trailing prefix star:
     *   this FTS4 configuration silently matches nothing when a prefix query
     *   is combined with OR (verified on-device: `"a"* OR "b"*` returns zero
     *   rows while `"a" OR "b"` works). The AND path keeps prefix matching, so
     *   recall for partial words still comes from the strict rungs.
     */
    fun buildFromTerms(terms: List<String>, conjunction: Conjunction = Conjunction.AND): String? {
        val cleaned = terms
            .map { it.trim { c -> c in trailingPunctuation }.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_TERMS)
        if (cleaned.isEmpty()) return null
        if (conjunction == Conjunction.OR) {
            return cleaned.joinToString(" OR ") { "\"${escape(it)}\"" }
        }
        return cleaned.joinToString(" ") { "\"${escape(it)}\"*" }
    }

    private fun tokenize(rawQuery: String): List<String> =
        rawQuery
            .split(Regex("\\s+"))
            .map { it.trim { c -> c in trailingPunctuation } }
            .filter { it.isNotBlank() }
            .take(10) // bound expression size; extra terms add little relevance

    /**
     * Escapes a term for an FTS4 string literal. A double quote is doubled,
     * which is how FTS4 embeds one inside a quoted phrase.
     */
    private fun escape(term: String): String = term.replace("\"", "\"\"")

    /** Escapes `%`, `_` and `\` for use inside a SQL LIKE pattern. */
    fun escapeLike(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (c == '%' || c == '_' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private const val MAX_TERMS = 12
}
