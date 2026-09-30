package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.DateCandidate
import com.ssintelligence.app.domain.model.OcrBlock
import com.ssintelligence.app.domain.model.OcrLevel
import com.ssintelligence.app.domain.model.OtpCandidate
import com.ssintelligence.app.domain.model.PhoneCandidate
import com.ssintelligence.app.domain.model.PriceCandidate
import com.ssintelligence.app.domain.model.UrlCandidate

/**
 * Aggregates the individual extractors into one pass over OCR output (§20).
 *
 * Extractors are independent: a failure in one must never prevent the others
 * from contributing, so each call is guarded individually and the outcome is
 * reported alongside the results for logging/diagnostics.
 */
class MetadataExtractor(
    private val urlExtractor: UrlExtractor = UrlExtractor(),
    private val dateExtractor: DateExtractor = DateExtractor(),
    private val phoneExtractor: PhoneExtractor = PhoneExtractor(),
    private val priceExtractor: PriceExtractor = PriceExtractor(),
    private val otpExtractor: OtpExtractor = OtpExtractor(),
) {
    data class Extracted(
        val urls: List<UrlCandidate>,
        val dates: List<DateCandidate>,
        val phones: List<PhoneCandidate>,
        val prices: List<PriceCandidate>,
        val otps: List<OtpCandidate>,
        /** Extractors that threw, for diagnostics only. */
        val failures: List<String>,
    )

    fun extract(text: String): Extracted {
        val failures = mutableListOf<String>()
        fun <T> guard(name: String, block: () -> List<T>): List<T> = try {
            block()
        } catch (t: Throwable) {
            failures += name
            emptyList()
        }

        return Extracted(
            urls = guard("urls") { urlExtractor.extract(text) },
            dates = guard("dates") { dateExtractor.extract(text) },
            phones = guard("phones") { phoneExtractor.extract(text) },
            prices = guard("prices") { priceExtractor.extract(text) },
            otps = guard("otps") { otpExtractor.extract(text) },
            failures = failures,
        )
    }

    /**
     * Joins OCR lines in reading order so extraction sees natural text
     * (a keyword on one line and a code on the next should still correlate).
     */
    fun joinText(blocks: List<OcrBlock>): String =
        blocks.filter { it.level == OcrLevel.LINE && it.text.isNotBlank() }
            .joinToString("\n") { it.text }
}
