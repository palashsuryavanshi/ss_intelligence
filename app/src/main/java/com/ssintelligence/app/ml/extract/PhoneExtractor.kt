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
 *
 * The recognition itself lives in [IndianPhoneNumbers] so the Phase 2 query
 * parser normalizes with exactly the same rules.
 */
class IndianPhoneStrategy : PhoneMatchStrategy {
    override val country: String = IndianPhoneNumbers.COUNTRY

    override fun find(text: String): List<PhoneCandidate> =
        IndianPhoneNumbers.find(text, suppressIdentifierContext = true)
}
