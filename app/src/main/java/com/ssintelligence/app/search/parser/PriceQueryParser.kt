package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.Currency
import com.ssintelligence.app.search.PriceFilter

/**
 * Understands prices in a search query (§9, §10).
 *
 * The parser is deliberately reluctant. A bare number is **not** a price:
 * "Order ID: 39999" must not become a price filter, so a number is only treated
 * as money when the query gives a reason — a comparison operator, a currency
 * marker, or an explicit word such as "price" or "cost".
 *
 * Currencies are normalized to ISO-4217 but never converted. `$400` and
 * `₹400` remain different currencies, so a cross-currency query correctly
 * returns nothing instead of comparing unlike amounts.
 *
 * Claimed spans
 * -------------
 * The span covers the whole price *expression* — the operator, the trigger word
 * and the currency marker, not just the digits. Without that, "below" would
 * survive as a search term and "phones below ₹40,000" would look for the word
 * "below" in OCR text.
 */
class PriceQueryParser {

    /**
     * Numbers with thousands separators, then bare numbers.
     *
     * The separator group accepts two *or* three digits so both conventions
     * parse as one token: `39,999`, `1,00,000` and `40 000`. A space is
     * accepted too, because "below 40 000" is how a person often writes it.
     */
    private val amountPattern = Regex("""\d{1,3}(?:[,\s]\d{2,3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

    private val symbols = setOf('₹', '$', '€', '£')

    /** "between 10000 and 20000", "between ₹10,000 and ₹20,000". */
    private val betweenPattern = Regex(
        """(?i)\bbetween\s+(?:([₹$€£])\s?)?(\d[\d,\s]*(?:\.\d{1,2})?)\s*(?:and|to|-|–|—)\s*(?:([₹$€£])\s?)?(\d[\d,\s]*(?:\.\d{1,2})?)""",
    )

    /** Comparison operators, longest phrases first so "no more than" wins. */
    private val atMostWords = listOf(
        "no more than", "not more than", "lesser than", "less than", "up to",
        "at most", "cheaper than", "below", "under", "within", "maximum of",
        "max of", "maximum", "max", "upto",
    )

    private val atLeastWords = listOf(
        "no less than", "not less than", "greater than", "more than", "at least",
        "starting from", "starting at", "above", "over", "minimum of", "min of",
        "minimum", "min",
    )

    private val approximateWords = listOf(
        "approximately", "approximatly", "approx", "around", "roughly", "nearly",
        "about", "close to", "near", "circa", "somewhere near",
    )

    /**
     * Words that make a number a price even without a currency or operator.
     * "priced at ₹x" and "cost 39999" both rely on this.
     */
    private val triggerWords = listOf(
        "price", "prices", "cost", "costs", "costing", "costed", "priced", "pricing",
        "worth", "paying", "paid", "pay", "budget", "amount", "for", "at", "of",
        "mrp", "rate", "total", "billed", "bill", "sell", "sold", "only",
    )

    /**
     * Words that make a number an *identifier* instead. Checked before
     * [triggerWords] so "order id 39999" is never read as a price (§44).
     */
    private val identifierWords = listOf(
        "id", "ids", "no", "nos", "num", "number", "order", "order id", "order no",
        "invoice", "invoice no", "bill no", "receipt", "receipt no", "ref", "refs",
        "reference", "reference no", "txn", "txn id", "transaction", "transaction id",
        "upi", "upi id", "upi ref", "booking", "booking id", "booking no",
        "aadhaar", "aadhaar no", "pan", "pan no", "pin", "pin no", "account",
        "account no", "otp", "code", "voucher", "coupon", "tracking", "awb", "rn",
        "imei", "isbn", "sku",
    )

    /**
     * Words the price expression may absorb to its left, longest first so
     * "no more than" is preferred over "than".
     */
    private val leftWords: List<String> =
        (atMostWords + atLeastWords + approximateWords + triggerWords)
            .distinct()
            .sortedByDescending { it.length }

    /**
     * Tolerance for "around" / "approximately", as a fraction of the amount.
     *
     * ±5% is the documented rule (§9): tight enough that "around ₹40,000" does
     * not swallow a ₹30,000 phone, wide enough to absorb OCR reading a price as
     * ₹39,999 when the user remembers ₹40,000. The engine's relaxed rung uses
     * this same number, so a relaxed result is never a wider surprise than the
     * one the user was already shown.
     */
    val toleranceFraction: Double = 0.05

    private enum class Operator { AT_MOST, AT_LEAST, APPROXIMATE }

    fun parse(text: String, consumed: List<QuerySpan> = emptyList()): List<ParsedSpan<PriceFilter>> {
        if (text.isBlank()) return emptyList()
        val claimed = consumed.toMutableList()
        val out = mutableListOf<ParsedSpan<PriceFilter>>()

        for (match in betweenPattern.findAll(text)) {
            val span = QuerySpan(match.range.first, match.range.last + 1)
            if (overlapsAny(match.range, claimed)) continue
            val low = parseAmount(match.groupValues[2]) ?: continue
            val high = parseAmount(match.groupValues[4]) ?: continue
            if (low <= 0.0 || high <= 0.0 || low > high) continue
            val currency = match.groupValues[1].let { Currency.resolve(it) }
                ?: match.groupValues[3].let { Currency.resolve(it) }
            claimed += span
            out += ParsedSpan(PriceFilter.Range(currency, low, high), span)
        }

        for (match in amountPattern.findAll(text)) {
            if (overlapsAny(match.range, claimed)) continue
            val amount = parseAmount(match.value) ?: continue
            if (amount <= 0.0 || amount >= 1e12) continue

            val amountStart = match.range.first
            val amountEnd = match.range.last + 1
            val windowStart = maxOf(0, amountStart - PROBE_WINDOW)
            val window = text.substring(windowStart, amountStart).lowercase()
            val probe = window.trimEnd(*PROBE_TRIM)
            // Absolute position just past the end of [probe]: the characters
            // trimmed off sat between the probe and the digits.
            val probeEnd = amountStart - (window.length - probe.length)

            val prefix = currencyBefore(text, amountStart)
            val suffix = if (prefix.code == null) currencyAfter(text, amountEnd) else null
            val currency = prefix.code ?: suffix?.code

            // A currency written as a word sits between the operator and the
            // digits ("under Rs 40000"), so it has to be stepped over before
            // the operator can be found. Currency *symbols* are already stripped
            // by [PROBE_TRIM]; words are not. Only a word that actually resolved
            // to a currency is removed — otherwise "price 40000" would lose the
            // very word that made it a price.
            val currencyWordLength = if (prefix.isWord && prefix.code != null) {
                prefix.end - prefix.start
            } else {
                0
            }
            val operatorProbe = probe.dropLast(currencyWordLength).trimEnd()
            // Absolute position just past [operatorProbe], used to place the
            // left-hand end of the claimed span.
            val operatorProbeEnd = probeEnd - (probe.length - operatorProbe.length)

            if (matchTrailing(operatorProbe, identifierWords) != null) continue

            val comparison = operatorFor(operatorProbe)
            val hasTrigger = matchTrailing(operatorProbe, triggerWords) != null
            if (comparison == null && currency == null && !hasTrigger) {
                // No reason to believe this is money. Leave it as a text term.
                continue
            }

            val filter = when (comparison) {
                Operator.AT_MOST -> PriceFilter.AtMost(currency, amount)
                Operator.AT_LEAST -> PriceFilter.AtLeast(currency, amount)
                Operator.APPROXIMATE -> PriceFilter.Range(
                    currency = currency,
                    min = (amount * (1 - toleranceFraction)).coerceAtLeast(0.0),
                    max = amount * (1 + toleranceFraction),
                )

                null -> PriceFilter.Exact(currency, amount)
            }

            val span = expressionSpan(
                amountStart = amountStart,
                amountEnd = amountEnd,
                probe = operatorProbe,
                probeEnd = operatorProbeEnd,
                windowStart = windowStart,
                absorbWords = comparison != null || hasTrigger,
                currencyStart = prefix.start,
                currencyEnd = suffix?.end,
            )
            claimed += span
            out += ParsedSpan(filter, span)
        }

        return out
    }

    /**
     * The span covering the whole price expression.
     *
     * Walks left over a short run of operator/trigger words ("priced at"),
     * then over a currency word written before the amount ("Rs 40000"), and
     * right over a currency word written after it ("39999 rupees").
     *
     * `probeEnd` is the absolute position just past the probe, so each step
     * knows exactly where the word it just matched began.
     */
    private fun expressionSpan(
        amountStart: Int,
        amountEnd: Int,
        probe: String,
        probeEnd: Int,
        windowStart: Int,
        absorbWords: Boolean,
        currencyStart: Int,
        currencyEnd: Int?,
    ): QuerySpan {
        var start = amountStart
        var end = amountEnd

        if (absorbWords) {
            var remaining = probe
            var cursor = probeEnd
            var steps = 0
            while (steps < MAX_LEFT_WORDS) {
                val length = matchTrailing(remaining, leftWords) ?: break
                val wordStart = cursor - length
                if (wordStart < windowStart) break
                start = wordStart
                // Drop the word and the separator that followed it, so the next
                // word's end is one gap earlier than this word's start.
                val afterDrop = remaining.dropLast(length)
                val gap = afterDrop.length - afterDrop.trimEnd().length
                remaining = afterDrop.trimEnd()
                cursor = wordStart - gap
                steps++
            }
        }

        if (currencyStart < start) start = currencyStart.coerceAtLeast(0)
        if (currencyEnd != null) end = maxOf(end, currencyEnd)
        return QuerySpan(start, end)
    }

    private fun operatorFor(probe: String): Operator? = when {
        matchTrailing(probe, atMostWords) != null -> Operator.AT_MOST
        matchTrailing(probe, atLeastWords) != null -> Operator.AT_LEAST
        matchTrailing(probe, approximateWords) != null -> Operator.APPROXIMATE
        else -> null
    }

    /**
     * A currency marker found next to an amount.
     *
     * [start] and [end] delimit the marker itself, not the gap between it and
     * the digits, so [end] - [start] is the marker's length. [isWord] marks the
     * alphabetic forms ("Rs", "rupees"), which the operator scan has to step
     * over.
     */
    private data class CurrencyHit(
        val code: String?,
        val start: Int,
        val end: Int,
        val isWord: Boolean = false,
    )

    /**
     * Length of the candidate phrase this text ends with, or null.
     *
     * A word boundary is required after the phrase is stripped off, so
     * "underground" is not read as the operator "under". Multi-word candidates
     * ("up to", "less than") are matched whole, which is why this looks at the
     * tail of the probe rather than at a single word.
     */
    private fun matchTrailing(text: String, candidates: List<String>): Int? =
        candidates.firstOrNull { candidate ->
            if (!text.endsWith(candidate)) return@firstOrNull false
            val before = text.dropLast(candidate.length).lastOrNull()
            before == null || !before.isLetterOrDigit()
        }?.length

    private fun currencyBefore(text: String, amountStart: Int): CurrencyHit {
        var i = amountStart - 1
        var skipped = 0
        while (i >= 0 && (text[i] == ' ' || text[i] == '-') && skipped < 2) {
            i--
            skipped++
        }
        if (i < 0) return CurrencyHit(null, amountStart, amountStart)
        if (text[i] in symbols) {
            return CurrencyHit(Currency.resolve(text[i].toString()), i, i + 1)
        }
        // Only the alphabetic run immediately before the digits is a candidate.
        // A fixed-width window would pick up "below Rs" and fail to resolve.
        var wordStart = i + 1
        while (wordStart > 0 && text[wordStart - 1].isLetter()) wordStart--
        val word = text.substring(wordStart, i + 1)
        if (word.isEmpty() || word.length > MAX_CURRENCY_WORD) {
            return CurrencyHit(null, amountStart, amountStart)
        }
        return CurrencyHit(Currency.resolve(word), wordStart, i + 1, isWord = true)
    }

    private fun currencyAfter(text: String, amountEnd: Int): CurrencyHit {
        var i = amountEnd
        var skipped = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '-') && skipped < 2) {
            i++
            skipped++
        }
        if (i >= text.length) return CurrencyHit(null, amountEnd, amountEnd)
        if (text[i] in symbols) {
            return CurrencyHit(Currency.resolve(text[i].toString()), i, i + 1)
        }
        var end = i
        while (end < text.length && text[end].isLetter()) end++
        if (end == i) return CurrencyHit(null, amountEnd, amountEnd)
        return CurrencyHit(Currency.resolve(text.substring(i, end)), i, end, isWord = true)
    }

    private fun parseAmount(raw: String?): Double? {
        val cleaned = raw?.replace(",", "")?.replace(" ", "") ?: return null
        if (cleaned.count { it == '.' } > 1) return null
        return cleaned.toDoubleOrNull()
    }

    private fun overlapsAny(range: IntRange, spans: List<QuerySpan>): Boolean =
        spans.any { it.start <= range.last && range.first < it.end }

    private companion object {
        /** "priced at ₹x" is two words; three is already generous. */
        const val MAX_LEFT_WORDS = 3

        /** How much text before an amount is inspected for an operator. */
        const val PROBE_WINDOW = 64

        /** Longest word that may be read as a currency marker ("rupees"). */
        const val MAX_CURRENCY_WORD = 8

        /** Characters that may sit between an operator and its amount. */
        val PROBE_TRIM = charArrayOf(
            ' ', '₹', '$', '€', '£', '.', ',', ':', ';', '#', '-', '–', '=',
        )
    }
}
