package com.ssintelligence.app.search.parser

import com.ssintelligence.app.ml.extract.IndianPhoneNumbers

/**
 * Understands phone-number queries (§14).
 *
 * Normalization is delegated to [IndianPhoneNumbers] — the exact same code the
 * OCR extractor uses — so `9876543210`, `+91 98765 43210` and `09876543210`
 * all collapse to `+919876543210` and match the stored value with one indexed
 * equality check. No fuzzy or partial matching is performed: a phone number is
 * an exact identifier, and a "close" phone number is a different person.
 *
 * Unlike OCR, the identifier-keyword guard is disabled. Someone who types a
 * number into the search box means that number, even if they typed "order
 * 9876543210".
 */
class PhoneQueryParser {

    fun parse(text: String, consumed: List<QuerySpan> = emptyList()): List<ParsedSpan<String>> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<ParsedSpan<String>>()
        for (candidate in IndianPhoneNumbers.find(text, suppressIdentifierContext = false)) {
            val index = text.indexOf(candidate.rawText)
            if (index < 0) continue
            val span = QuerySpan(index, index + candidate.rawText.length)
            if (consumed.any { it.overlaps(span) }) continue
            // A currency marker next to the digits means this is an amount, not
            // a phone number.
            if (hasCurrencyMarkerNear(text, span)) continue
            out += ParsedSpan(candidate.normalized, span)
        }
        return out
    }

    private val currencyMarkers = listOf("₹", "$", "€", "£", "rs", "inr", "usd", "eur", "gbp")

    private fun hasCurrencyMarkerNear(text: String, span: QuerySpan): Boolean {
        val before = text.substring(maxOf(0, span.start - 6), span.start).lowercase().trim()
        if (before.isNotEmpty() && currencyMarkers.any { before.endsWith(it) }) return true
        val after = text.substring(span.end, minOf(text.length, span.end + 6)).lowercase().trimStart()
        if (after.isNotEmpty() && currencyMarkers.any { after.startsWith(it) }) return true
        return false
    }
}
