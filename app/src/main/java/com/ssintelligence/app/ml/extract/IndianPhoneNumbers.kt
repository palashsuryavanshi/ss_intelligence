package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.PhoneCandidate

/**
 * Shared Indian phone-number recognition, used by both the OCR extractor (§14)
 * and the Phase 2 query parser (§14 of the search spec).
 *
 * The two callers want slightly different behaviour, controlled by
 * [suppressIdentifierContext]:
 * - OCR text contains "Order ID: 9876543210" often enough that the id-keyword
 *   guard prevents a lot of false positives.
 * - A search query is deliberate user input. If someone types a number, they
 *   mean that number, so the guard is skipped.
 */
object IndianPhoneNumbers {

    const val COUNTRY = "IN"

    /** `+91 98765 43210`, `09876543210`, `9876543210`, `91 98765 43210`. */
    val patterns: List<Regex> = listOf(
        Regex("""\+91[\s\-]*[6-9]\d{4}[\s\-]*\d{5}"""),
        Regex("""\+91[6-9]\d{9}"""),
        Regex("""(?<!\d)91[\s\-]?[6-9]\d{4}[\s\-]?\d{5}(?!\d)"""),
        Regex("""(?<!\d)0?[6-9]\d{4}[\s\-]?\d{5}(?!\d)"""),
    )

    /**
     * Words that mark a digit run as an identifier rather than a phone number.
     * Anchored to the end of the window so it must *immediately* precede.
     */
    private val idKeywords = Regex(
        """\b(order|booking|transaction|txn|upi|ref|reference|receipt|bill|invoice|account|aadhaar|pan|pin|id|no)\b[\s:.\-#]*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Canonical form, or null when the digit count or leading digits do not fit
     * an Indian number. One representation only, so query-side and index-side
     * comparison is a single indexed equality check.
     */
    fun normalize(digits: String): String? = when {
        digits.length == 12 && digits.startsWith("91") -> "+$digits"
        digits.length == 11 && digits.startsWith("0") && digits[1] in '6'..'9' ->
            "+91" + digits.drop(1)

        digits.length == 10 && digits[0] in '6'..'9' -> "+91$digits"
        else -> null
    }

    fun find(
        text: String,
        suppressIdentifierContext: Boolean = true,
    ): List<PhoneCandidate> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<PhoneCandidate>()
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val digits = match.value.filter { it.isDigit() }
                val normalized = normalize(digits) ?: continue
                if (suppressIdentifierContext && isIdentifierContext(text, match.range.first)) {
                    continue
                }
                out += PhoneCandidate(
                    rawText = match.value.trim(),
                    normalized = normalized,
                    country = COUNTRY,
                )
            }
        }
        return out.distinctBy { it.normalized }
    }

    private fun isIdentifierContext(text: String, matchStart: Int): Boolean {
        val windowStart = maxOf(0, matchStart - 24)
        return idKeywords.containsMatchIn(text.substring(windowStart, matchStart))
    }
}
