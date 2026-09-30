package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.ContentType

/**
 * Recognizes explicit "screenshots that contain X" requests (§40, §30).
 *
 * These phrases are *structural*, not textual: "show duplicate screenshots"
 * must not search for the word "duplicate", it must list rows whose
 * `duplicate_of_id` is set. The matched phrase is therefore consumed here, so
 * the keyword extractor never turns it into a search term.
 *
 * Content types implied by a parsed value (a URL, a price, a phone number) are
 * added by [QueryParser] instead, so each concept has exactly one place that
 * decides it.
 */
class ContentTypeParser {

    private data class Rule(val type: ContentType, val pattern: Regex)

    private val rules = listOf(
        Rule(
            ContentType.DUPLICATES,
            Regex("""(?i)\b(?:near[\s-]?)?duplicates?\b|\bidentical\s+(?:screenshots?|images?|copies)\b"""),
        ),
        Rule(
            ContentType.OTPS,
            Regex(
                """(?i)\b(?:otp|o\.t\.p)s?\b|\bone[\s-]?time\s+(?:passwords?|codes?|pins?)\b|""" +
                    """\b(?:verification|verify|security|login|auth)\s+codes?\b""",
            ),
        ),
        Rule(
            ContentType.URLS,
            Regex(
                """(?i)\b(?:with|containing|has|have|including|shows?)\s+(?:a\s+|an\s+|any\s+)?""" +
                    """(?:urls?|links?|websites?|web\s+pages?)\b|\burls?\b|\blinks?\b|\bwebsites?\b""",
            ),
        ),
        Rule(
            ContentType.PRICES,
            Regex("""(?i)\bwith\s+(?:a\s+)?prices?\b|\bcontaining\s+(?:a\s+)?prices?\b|\bprice\s+lists?\b"""),
        ),
        Rule(
            ContentType.PHONES,
            Regex(
                """(?i)\bwith\s+(?:a\s+)?(?:phone|contact|mobile)\s*(?:numbers?)?\b|""" +
                    """\bcontaining\s+(?:a\s+)?phone\s*numbers?\b|\bphone\s+numbers?\b|\bcontacts?\b""",
            ),
        ),
        Rule(
            ContentType.DATES,
            Regex("""(?i)\bwith\s+(?:a\s+)?dates?\b|\bcontaining\s+(?:a\s+)?dates?\b|\bdate\s+range\b"""),
        ),
    )

    data class Result(val types: Set<ContentType>, val spans: List<QuerySpan>)

    fun parse(text: String, consumed: List<QuerySpan> = emptyList()): Result {
        if (text.isBlank()) return Result(emptySet(), emptyList())
        val types = mutableSetOf<ContentType>()
        val spans = mutableListOf<QuerySpan>()

        for (rule in rules) {
            for (match in rule.pattern.findAll(text)) {
                val span = QuerySpan(match.range.first, match.range.last + 1)
                if (consumed.any { it.overlaps(span) }) continue
                if (spans.any { it.overlaps(span) }) continue
                types += rule.type
                spans += span
            }
        }
        return Result(types, spans)
    }
}
