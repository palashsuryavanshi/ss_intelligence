package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.PhoneCandidate

/**
 * Phone number detection (§14).
 *
 * Indian numbers are prioritized; additional countries plug in as new
 * [PhoneMatchStrategy] implementations passed to [PhoneExtractor].
 */
interface PhoneMatchStrategy {
    val country: String
    fun find(text: String): List<PhoneCandidate>
}

class PhoneExtractor(
    private val strategies: List<PhoneMatchStrategy> = listOf(IndianPhoneStrategy()),
) {
    fun extract(text: String): List<PhoneCandidate> {
        if (text.isBlank()) return emptyList()
        return strategies
            .flatMap { it.find(text) }
            .distinctBy { it.normalized }
    }
}

/**
 * Matches Indian mobile numbers:
 * `+91 98765 43210`, `+91-9876543210`, `9876543210`, `09876543210`, `91 98765 43210`.
 *
 * Guard rails: the 10-digit core must start with 6–9 (Indian mobile series),
 * must not be embedded in a longer digit run, and must not sit next to
 * identifier keywords (order/booking/transaction IDs, UPI refs, Aadhaar…).
 */
class IndianPhoneStrategy : PhoneMatchStrategy {
    override val country: String = "IN"

    private val patterns = listOf(
        // +91 with separators, e.g. "+91 98765 43210", "+91-98765-43210"
        Regex("""\+91[\s\-]*[6-9]\d{4}[\s\-]*\d{5}"""),
        // +91 compact
        Regex("""\+91[6-9]\d{9}"""),
        // 91 prefix without '+'
        Regex("""(?<!\d)91[\s\-]?[6-9]\d{4}[\s\-]?\d{5}(?!\d)"""),
        // Plain 10-digit, optional trunk 0
        Regex("""(?<!\d)0?[6-9]\d{4}[\s\-]?\d{5}(?!\d)"""),
    )

    private val idKeywords = Regex(
        """\b(order|booking|transaction|txn|upi|ref|reference|receipt|bill|invoice|account|aadhaar|pan|pin|id|no)\b[\s:.\-#]*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun find(text: String): List<PhoneCandidate> {
        val out = mutableListOf<PhoneCandidate>()
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val raw = match.value
                // Skip when the digits run is part of a longer run that the
                // regex boundary missed due to separators, e.g. "198765432109".
                val digits = raw.filter { it.isDigit() }
                val normalized = normalize(digits) ?: continue
                if (isIdentifierContext(text, match.range.first)) continue
                out += PhoneCandidate(rawText = raw.trim(), normalized = normalized, country = country)
            }
        }
        return out.distinctBy { it.normalized }
    }

    private fun normalize(digits: String): String? {
        return when {
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            digits.length == 11 && digits.startsWith("0") && digits[1] in '6'..'9' ->
                "+91" + digits.drop(1)

            digits.length == 10 && digits[0] in '6'..'9' -> "+91$digits"
            else -> null
        }
    }

    private fun isIdentifierContext(text: String, matchStart: Int): Boolean {
        val windowStart = maxOf(0, matchStart - 24)
        return idKeywords.containsMatchIn(text.substring(windowStart, matchStart))
    }
}
