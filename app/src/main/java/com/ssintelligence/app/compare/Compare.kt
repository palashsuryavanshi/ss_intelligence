package com.ssintelligence.app.compare

import com.ssintelligence.app.domain.model.ScreenshotDetail

/**
 * OCR/text/metadata comparison of two screenshots (§26).
 *
 * Pixel-level diffing is deliberately out of scope: two screenshots of the
 * same page taken seconds apart differ in status-bar icons and ad rotation,
 * and a pixel diff would report noise as change. Comparing what the pipeline
 * read — prices, hosts, terms — reports what a person would call a difference.
 */
data class ScreenshotComparison(
    val firstId: Long,
    val secondId: Long,
    /** Prices only in the first screenshot. */
    val onlyInFirstPrices: List<String>,
    /** Prices only in the second screenshot. */
    val onlyInSecondPrices: List<String>,
    val onlyInFirstHosts: List<String>,
    val onlyInSecondHosts: List<String>,
    /** Terms gained in the second screenshot (top 20 by distinctiveness). */
    val addedTerms: List<String>,
    /** Terms lost from the first screenshot. */
    val removedTerms: List<String>,
    /** Plain-English change statements, e.g. "Price ₹39,999 → ₹41,999". */
    val changes: List<String>,
)

object CompareBuilder {

    fun compare(first: ScreenshotDetail, second: ScreenshotDetail): ScreenshotComparison {
        val firstPrices = first.prices.map { "${it.currency} ${it.amount}" }.toSet()
        val secondPrices = second.prices.map { "${it.currency} ${it.amount}" }.toSet()
        val firstHosts = first.urls.map { it.host }.toSet()
        val secondHosts = second.urls.map { it.host }.toSet()
        val firstTerms = termSet(first.screenshot.ocrText)
        val secondTerms = termSet(second.screenshot.ocrText)

        val changes = mutableListOf<String>()
        // A price that moved: same currency, different amount, one each side.
        // Stated as a change only when both sides have exactly one price in
        // that currency — otherwise it is an addition or removal, not a move.
        val currencies = (first.prices.map { it.currency } + second.prices.map { it.currency }).toSet()
        for (currency in currencies) {
            val a = first.prices.filter { it.currency == currency }.map { it.amount }
            val b = second.prices.filter { it.currency == currency }.map { it.amount }
            if (a.size == 1 && b.size == 1 && a.single() != b.single()) {
                changes += "Price ${format(currency, a.single())} → ${format(currency, b.single())}"
            }
        }
        val firstSite = firstHosts.firstOrNull()
        val secondSite = secondHosts.firstOrNull()
        if (firstSite != null && secondSite != null && firstSite != secondSite &&
            firstHosts.size == 1 && secondHosts.size == 1
        ) {
            changes += "Website $firstSite → $secondSite"
        }

        return ScreenshotComparison(
            firstId = first.screenshot.id,
            secondId = second.screenshot.id,
            onlyInFirstPrices = (firstPrices - secondPrices).sorted(),
            onlyInSecondPrices = (secondPrices - firstPrices).sorted(),
            onlyInFirstHosts = (firstHosts - secondHosts).sorted(),
            onlyInSecondHosts = (secondHosts - firstHosts).sorted(),
            addedTerms = (secondTerms - firstTerms).sorted().take(MAX_TERMS),
            removedTerms = (firstTerms - secondTerms).sorted().take(MAX_TERMS),
            changes = changes,
        )
    }

    private fun termSet(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9₹.,]+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun format(currency: String, amount: Double): String {
        val digits = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
        return when (currency) {
            "INR" -> "₹$digits"
            "USD" -> "$$digits"
            else -> "$digits $currency"
        }
    }

    private const val MAX_TERMS = 20
}
