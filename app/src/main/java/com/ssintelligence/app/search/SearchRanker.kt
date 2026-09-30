package com.ssintelligence.app.search

import com.ssintelligence.app.domain.model.ExtractedPhone
import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ExtractedUrl
import com.ssintelligence.app.domain.model.Screenshot

/**
 * Everything the ranker needs about one candidate.
 *
 * Candidates arrive from SQL already narrowed by the structured filters, so
 * only a bounded set (hundreds, not the whole library) is ever scored. The
 * ranker is therefore pure Kotlin over this snapshot: deterministic, and unit
 * testable without a database.
 */
data class RankCandidate(
    val screenshot: Screenshot,
    val prices: List<ExtractedPrice> = emptyList(),
    val urls: List<ExtractedUrl> = emptyList(),
    val phones: List<ExtractedPhone> = emptyList(),
    val otpCodes: List<String> = emptyList(),
)

/** One scored candidate plus the reasons it earned that score. */
data class RankedResult(
    val candidate: RankCandidate,
    val score: Int,
    val reasons: List<MatchReason>,
    val snippet: Snippet?,
)

/**
 * Relevance weights (§18).
 *
 * Configurable on purpose: the starting numbers in the spec are "a starting
 * point only", and a different library will want a different balance. Keeping
 * them in one value object means tuning ranking never means editing logic.
 */
data class RelevanceWeights(
    /** The exact phrase the user typed appears verbatim in the OCR text. */
    val exactPhrase: Int = 100,
    /** Every search term is present. */
    val allTerms: Int = 60,
    /** Most terms are present (at least [mostTermsRatio]). */
    val mostTerms: Int = 40,
    /** A price matching the query exactly, or inside an "around" band. */
    val priceExact: Int = 50,
    /** A price near, but not inside, the requested band. */
    val priceApproximate: Int = 30,
    val urlMatch: Int = 40,
    val phoneMatch: Int = 50,
    val otpMatch: Int = 50,
    val dateMatch: Int = 30,
    val filenameMatch: Int = 20,
    val ocrMatch: Int = 10,
    /** Smallest contribution of recency; bounded so it can never outrank a match. */
    val recencyMax: Int = 6,
    /** Share of terms that counts as "most terms" (§18). */
    val mostTermsRatio: Double = 0.6,
) {
    companion object {
        /** The default balance from §18. */
        val Default = RelevanceWeights()
    }
}

/**
 * Deterministic relevance scoring (§18, §19, §20, §21).
 *
 * Design constraints:
 * - No randomness and no model. The same query over the same index always
 *   produces the same order, which is what makes the behaviour testable and the
 *   UI's "why did this come first" answer explainable.
 * - Scores are never presented to the user as a percentage or probability.
 *   There is no probability model behind these numbers, so the UI shows the
 *   [MatchReason] list instead (§27).
 * - Combined constraints dominate single ones: a screenshot matching both the
 *   phrase and the price outranks one matching only the phrase, because each
 *   dimension adds to the total (§21).
 */
class SearchRanker(
    private val weights: RelevanceWeights = RelevanceWeights.Default,
) {

    fun rank(
        query: SearchQuery,
        candidates: List<RankCandidate>,
        nowMillis: Long,
    ): List<RankedResult> {
        val terms = query.textTerms.map { it.lowercase() }
        val phrases = query.phrases.map { it.lowercase() }
        val needles = (phrases + terms).distinct()

        return candidates
            .map { candidate -> score(query, candidate, terms, phrases, needles, nowMillis) }
            .sortedWith(
                compareByDescending<RankedResult> { it.score }
                    // Recency breaks ties, then row id so the order is total and
                    // two identical rows can never swap between runs.
                    .thenByDescending { it.candidate.screenshot.dateAdded }
                    .thenBy { it.candidate.screenshot.id },
            )
    }

    private fun score(
        query: SearchQuery,
        candidate: RankCandidate,
        terms: List<String>,
        phrases: List<String>,
        needles: List<String>,
        nowMillis: Long,
    ): RankedResult {
        val reasons = mutableListOf<MatchReason>()
        var score = 0

        val ocr = normalize(candidate.screenshot.ocrText)
        val compactOcr = ocr.replace(" ", "")
        val filename = candidate.screenshot.filename.lowercase()

        // ---------------------------------------------------------- text
        if (terms.isNotEmpty()) {
            val hits = terms.filter { term -> ocr.contains(term) || filename.contains(term) }
            when {
                hits.size == terms.size -> score += weights.allTerms
                hits.size >= Math.ceil(terms.size * weights.mostTermsRatio).toInt() ->
                    score += weights.mostTerms
            }
            if (hits.isNotEmpty()) {
                score += weights.ocrMatch
                reasons += MatchReason(hits.first(), MatchKind.TERM)
            }
        }

        // Exact phrase beats scattered words: "Pixel 9a" over
        // "Pixel phone with Android 9a update" (§19). The compacted haystack
        // catches phrases broken by an OCR line break.
        phrases.forEach { phrase ->
            if (ocr.contains(phrase) || compactOcr.contains(phrase.replace(" ", ""))) {
                score += weights.exactPhrase
                reasons += MatchReason(phrase, MatchKind.PHRASE)
            }
        }

        terms.firstOrNull { term -> filename.contains(term) }?.let {
            score += weights.filenameMatch
            reasons += MatchReason(it, MatchKind.FILENAME)
        }

        // --------------------------------------------------------- prices
        for (filter in query.prices) {
            val best = candidate.prices
                .mapNotNull { price -> filter.closeness(price.amount, price.currency).takeIf { it > 0.0 }?.let { it to price } }
                .maxByOrNull { it.first }
                ?: continue
            val (closeness, price) = best
            score += if (closeness >= 1.0) {
                weights.priceExact
            } else {
                (weights.priceApproximate * closeness).toInt()
            }
            reasons += MatchReason(Currency.format(price.currency, price.amount), MatchKind.PRICE)
        }

        // ----------------------------------------------------------- URLs
        for (host in query.urls) {
            val matched = candidate.urls.firstOrNull { url -> hostMatches(host, url.host) }
            if (matched != null) {
                score += weights.urlMatch
                reasons += MatchReason(host, MatchKind.URL)
            }
        }

        // --------------------------------------------------------- phones
        for (number in query.phoneNumbers) {
            val matched = candidate.phones.firstOrNull { it.normalized == number }
            if (matched != null) {
                score += weights.phoneMatch
                reasons += MatchReason(matched.normalized, MatchKind.PHONE)
            }
        }

        // ----------------------------------------------------------- OTPs
        if (query.otpCodes.isNotEmpty() && candidate.otpCodes.any { it in query.otpCodes }) {
            score += weights.otpMatch
            // The value is never rendered. A one-time code in a result row, a
            // notification or a suggestion is a place a secret would leak (§15).
            reasons += MatchReason("One-time code", MatchKind.OTP)
        }

        // ----------------------------------------------------------- dates
        val range = query.timeRange
        if (range != null && range.contains(candidate.screenshot.dateAdded)) {
            score += weights.dateMatch
            query.dateFilters.firstOrNull()?.let {
                reasons += MatchReason(it.label, MatchKind.DATE)
            }
        }

        if (ContentType.DUPLICATES in query.contentTypes && candidate.screenshot.duplicateOfId != null) {
            reasons += MatchReason("Duplicate", MatchKind.DUPLICATE)
        }

        score += recencyBonus(candidate.screenshot.dateAdded, nowMillis)

        val snippet = SnippetBuilder.build(candidate.screenshot.ocrText, needles)
        return RankedResult(
            candidate = candidate,
            score = score,
            reasons = reasons.distinct(),
            snippet = snippet,
        )
    }

    /**
     * Recency contribution, bounded by [RelevanceWeights.recencyMax] so it can
     * break ties but never outrank a real match. Linear decay over a year.
     */
    private fun recencyBonus(dateAddedEpochSeconds: Long, nowMillis: Long): Int {
        val nowSeconds = nowMillis / 1000
        val ageDays = ((nowSeconds - dateAddedEpochSeconds) / 86_400L).coerceAtLeast(0L)
        val ratio = 1.0 - (ageDays / 365.0).coerceIn(0.0, 1.0)
        return (weights.recencyMax * ratio).toInt()
    }

    /** `amazon.in` matches `amazon.in` and `smile.amazon.in`, not `notamazon.in`. */
    private fun hostMatches(queryHost: String, storedHost: String): Boolean {
        val stored = storedHost.lowercase()
        return stored == queryHost || stored.endsWith(".$queryHost")
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim()
}
