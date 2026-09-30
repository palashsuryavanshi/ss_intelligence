package com.ssintelligence.app.search

/**
 * Builds FTS `MATCH` expressions from raw user input.
 *
 * Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM.
 * Prefers FTS MATCH over `LIKE '%query%'` (§25). All terms are AND-ed;
 * each term is quoted and prefix-matched so "pixel 9a" matches
 * "Pixel 9a 5G" but not "pixelated".
 */
object FtsQueryBuilder {
    private val trailingPunctuation = ".,;:!?\"'()[]{}".toSet()

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
     */
    fun build(rawQuery: String): String? {
        val tokens = rawQuery
            .split(Regex("\\s+"))
            .map { it.trim { c -> c in trailingPunctuation } }
            .filter { it.length >= 2 }
            .distinct()
            .take(10) // bound expression size; extra terms add little relevance
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it\"*" }
    }

    /** Escapes `%`, `_` and `\` for use inside a SQL LIKE pattern. */
    fun escapeLike(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (c == '%' || c == '_' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }
}
