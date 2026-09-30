package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.SearchIntent
import com.ssintelligence.app.search.SearchQuery

/**
 * Turns a sentence a person typed into a [SearchQuery] (§2, §22).
 *
 * The parsers are ordered, because each must claim regions of the query before
 * the next one looks at it:
 *
 * ```
 * date → URL → code → phone → price → content type → keywords
 * ```
 *
 * - **Date first.** "from September" must be consumed as a date, or "from"
 *   stays in the text and "from 2026" reads as a price comparison.
 * - **Codes and phones before price.** A 10-digit number is a phone, not an
 *   amount. Both parsers additionally refuse digits next to a currency marker.
 * - **Keywords last**, with every claimed span subtracted, so "₹39,999" is a
 *   price filter and not also the search term "39,999".
 *
 * Each parser is independent, so a new one can be inserted without changing the
 * others; only the ordering carries meaning.
 */
class QueryParser(
    private val dateParser: DateQueryParser = DateQueryParser(),
    private val urlParser: UrlQueryParser = UrlQueryParser(),
    private val otpParser: OtpQueryParser = OtpQueryParser(),
    private val phoneParser: PhoneQueryParser = PhoneQueryParser(),
    private val priceParser: PriceQueryParser = PriceQueryParser(),
    private val contentTypeParser: ContentTypeParser = ContentTypeParser(),
    private val keywordExtractor: KeywordExtractor = KeywordExtractor(),
    private val intentClassifier: IntentClassifier = IntentClassifier(),
) {

    fun parse(raw: String): SearchQuery {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return SearchQuery(originalQuery = raw, intent = SearchIntent.BROWSE)
        }

        val claimed = mutableListOf<QuerySpan>()

        val dates = dateParser.parse(trimmed, claimed)
        dates.forEach { claimed += it.span }

        val urls = urlParser.parse(trimmed, claimed)
        urls.forEach { claimed += it.span }

        val codes = otpParser.parse(trimmed, claimed)
        codes.forEach { claimed += it.span }

        val phones = phoneParser.parse(trimmed, claimed)
        phones.forEach { claimed += it.span }

        val prices = priceParser.parse(trimmed, claimed)
        prices.forEach { claimed += it.span }

        val contentTypes = contentTypeParser.parse(trimmed, claimed)
        contentTypes.spans.forEach { claimed += it }

        val keywords = keywordExtractor.extract(trimmed, claimed)

        // When a sentence contains more than one time expression the first one
        // wins. "September 2026" and "before January" contradict each other, and
        // quietly widening one of them would be worse than honouring the leftmost.
        val effectiveRange = dates.firstOrNull()?.value?.toTimeRange()

        val draft = SearchQuery(
            originalQuery = raw,
            intent = SearchIntent.SEARCH,
            textTerms = keywords.terms,
            phrases = keywords.phrases,
            prices = prices.map { it.value },
            dateFilters = dates.map { it.value },
            urls = urls.map { it.value },
            phoneNumbers = phones.map { it.value },
            otpCodes = codes.map { it.value },
            contentTypes = contentTypes.types,
            timeRange = effectiveRange,
        )

        return draft.copy(intent = intentClassifier.classify(trimmed, draft))
    }
}
