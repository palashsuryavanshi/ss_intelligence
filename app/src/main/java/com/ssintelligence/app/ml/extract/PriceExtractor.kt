package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.PriceCandidate

/**
 * Price / currency extraction (§15).
 *
 * Supported: ₹/Rs./Rs/INR, $/USD, €/EUR, £/GBP — as prefix symbols/codes or
 * suffix codes ("USD 299", "299 USD", "$299"). A currency marker is always
 * required, so plain numbers (OTPs, order IDs, counts) are never prices.
 */
class PriceExtractor {

    private data class Currency(val markers: List<String>, val iso: String, val symbolFirst: Boolean)

    // Order matters: longer markers first so "Rs." wins over "R"-style prefixes.
    private val currencies = listOf(
        Currency(listOf("₹"), "INR", symbolFirst = true),
        Currency(listOf("Rs.", "Rs"), "INR", symbolFirst = false),
        Currency(listOf("INR"), "INR", symbolFirst = false),
        Currency(listOf("$"), "USD", symbolFirst = true),
        Currency(listOf("USD"), "USD", symbolFirst = false),
        Currency(listOf("€"), "EUR", symbolFirst = true),
        Currency(listOf("EUR"), "EUR", symbolFirst = false),
        Currency(listOf("£"), "GBP", symbolFirst = true),
        Currency(listOf("GBP"), "GBP", symbolFirst = false),
    )

    private val number = """\d[\d,]*(?:\.\d{1,2})?"""

    private val nonPriceContext = Regex(
        """\b(otp|verification|verify|code|order|booking|transaction|txn|upi|ref|reference|receipt|bill|invoice|account|aadhaar|pan|pin|password|id|no|number|qty|quantity|items?|kg|km|cm|mm|gb|mb|%)[\s:.\-#]*$""",
        RegexOption.IGNORE_CASE,
    )

    private data class Compiled(val regex: Regex, val iso: String, val numberGroup: Int)

    private val compiled: List<Compiled> = buildList {
        for (c in currencies) {
            for (marker in c.markers) {
                val m = Regex.escape(marker)
                if (c.symbolFirst) {
                    // "$299", "₹ 39,999", "50$" (suffix form for symbols too)
                    add(Compiled(Regex("""$m\s?($number)"""), c.iso, 1))
                    add(Compiled(Regex("""($number)\s?$m"""), c.iso, 1))
                } else {
                    // Word-ish codes need boundaries: "Rs. 39,999", "USD 299", "299 INR".
                    // "Rs" without a dot must not match inside words ("Rsources").
                    val boundary = if (marker == "Rs") """(?<![A-Za-z])""" else """\b"""
                    add(Compiled(Regex("""$boundary$m\.?\s?($number)"""), c.iso, 1))
                    add(Compiled(Regex("""($number)\s?$boundary$m\b"""), c.iso, 1))
                }
            }
        }
    }

    fun extract(text: String): List<PriceCandidate> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<PriceCandidate>()
        for ((regex, iso, numberGroup) in compiled) {
            for (match in regex.findAll(text)) {
                val raw = match.value.trim()
                val amount = parseAmount(match.groups[numberGroup]?.value ?: continue)
                    ?: continue
                if (isNonPriceContext(text, match.range.first)) continue
                out += PriceCandidate(rawText = raw, currency = iso, amount = amount)
            }
        }
        // Dedupe overlapping prefix/suffix double-matches ("$299" vs "299" hits).
        return out.distinctBy { it.rawText to it.currency }
    }

    private fun parseAmount(raw: String): Double? {
        // Reject malformed groupings like "39,,999" but accept both Indian
        // (1,00,000) and Western (100,000) groupings by simply stripping commas.
        if (",," in raw) return null
        val amount = raw.replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0.0 || amount >= 1e12) return null
        return amount
    }

    private fun isNonPriceContext(text: String, matchStart: Int): Boolean {
        val windowStart = maxOf(0, matchStart - 28)
        return nonPriceContext.containsMatchIn(text.substring(windowStart, matchStart))
    }
}
