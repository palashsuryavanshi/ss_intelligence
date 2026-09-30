package com.ssintelligence.app.search.parser

/**
 * A word or number found in the query, with its position in the original string.
 *
 * Keeping offsets lets the parsers claim regions of the query: once the price
 * parser has consumed "₹39,999", the keyword extractor knows not to treat
 * "39,999" as a search term too. Without this, every structured value would
 * also be searched for as free text and results would be polluted.
 */
data class QueryToken(
    val text: String,
    val lower: String,
    val start: Int,
    val end: Int,
)

/** A half-open `[start, end)` region of the query claimed by one parser. */
data class QuerySpan(val start: Int, val end: Int) {

    fun overlaps(other: QuerySpan): Boolean = start < other.end && other.start < end

    operator fun contains(index: Int): Boolean = index in start until end

    companion object {
        fun around(token: QueryToken, extraChars: Int = 0): QuerySpan =
            QuerySpan(
                start = (token.start - extraChars).coerceAtLeast(0),
                end = token.end + extraChars,
            )
    }
}

/**
 * Splits a query into tokens without losing the characters between them.
 *
 * The pattern is ordered so that:
 * - `39,999` and `1,00,000` stay single tokens (comma-grouped number first),
 * - `9a`, `iPhone15` and `M3` stay single tokens (digit-leading word second),
 * - bare numbers like `9876543210` still tokenize (fallback third).
 *
 * Currency symbols and punctuation are separators, not tokens: "₹39,999"
 * yields `39,999`, which the price parser resolves using the surrounding text.
 */
object QueryTokenizer {

    private val tokenPattern = Regex(
        """\d{1,3}(?:,\d{2,3})+(?:\.\d+)?|\d*\p{L}[\p{L}\p{N}]*|[\p{L}\p{N}]+""",
    )

    fun tokenize(query: String): List<QueryToken> =
        tokenPattern.findAll(query).map { match ->
            QueryToken(
                text = match.value,
                lower = match.value.lowercase(),
                start = match.range.first,
                end = match.range.last + 1,
            )
        }.toList()
}

/** A parse result carrying both what was found and where it came from. */
data class ParsedSpan<T>(
    val value: T,
    val span: QuerySpan,
)

/** Output of [KeywordExtractor]: individual terms plus preserved phrases. */
data class PhraseRun(
    val terms: List<String>,
    val phrases: List<String>,
) {
    val isEmpty: Boolean get() = terms.isEmpty() && phrases.isEmpty()
}
