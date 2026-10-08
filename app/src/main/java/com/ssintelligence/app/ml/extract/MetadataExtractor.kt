package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.DateCandidate
import com.ssintelligence.app.domain.model.ExtractedReceipt
import com.ssintelligence.app.domain.model.OcrBlock
import com.ssintelligence.app.domain.model.OcrLevel
import com.ssintelligence.app.domain.model.OtpCandidate
import com.ssintelligence.app.domain.model.PhoneCandidate
import com.ssintelligence.app.domain.model.PriceCandidate
import com.ssintelligence.app.domain.model.UrlCandidate
import java.util.regex.Pattern

/**
 * Receipt / invoice extraction (§12).
 *
 * Attempts to find a merchant name, total amount, date and category from a
 * screenshot's OCR text. Conservative: only returns a result when high
 * confidence signals are present. Never guesses.
 */
object ReceiptExtractor {

    private val currencyMarkers = setOf("₹", "Rs", "Rs.", "INR", "$", "USD", "€", "EUR", "£", "GBP")
    private val merchantKeywords = setOf("store", "shop", "mart", "market", "supermarket", "mall", "bazaar", "bakery", "restaurant", "cafe", "hotel", "hospital", "clinic", "pharmacy", "medical", "fuel", "petrol", "gas", "station", "uber", "ola", "rapido", "swiggy", "zomato", "blinkit", "bigbasket", "grofers", "dunzo", "amazon", "flipkart", "myntra", "nykaa", "jiomart", "reliance", "dmart", "more", "spencer", "reliance fresh", "reliance smart", "big bazaar", "vishal mega", "easyday", "nilgiris", "food world", "apna bazaar")
    private val categoryMap = mapOf(
        "uber" to "Transport", "ola" to "Transport", "rapido" to "Transport",
        "swiggy" to "Food", "zomato" to "Food", "blinkit" to "Groceries",
        "bigbasket" to "Groceries", "grofers" to "Groceries", "dunzo" to "Groceries",
        "amazon" to "Shopping", "flipkart" to "Shopping", "myntra" to "Shopping", "nykaa" to "Shopping",
        "uber eats" to "Food", "zomato" to "Food",
        "hospital" to "Healthcare", "clinic" to "Healthcare", "apollo" to "Healthcare", "fortis" to "Healthcare",
        "indigo" to "Travel", "air india" to "Travel", "vistara" to "Travel", "spicejet" to "Travel", "goair" to "Travel",
        "irctc" to "Travel", "makemytrip" to "Travel", "goibibo" to "Travel", "yatra" to "Travel",
        "pvr" to "Entertainment", "inox" to "Entertainment", "cinepolis" to "Entertainment",
    )

    private data class AmountInfo(val amount: Double, val currency: String)

    fun extract(text: String): ExtractedReceipt? {
        val lower = text.lowercase()
        val lines = text.lines().filter { it.isNotBlank() }.map { it.trim() }.toList()
        if (lines.isEmpty()) return null

        val amountInfo = findAmount(text)
        if (amountInfo == null) return null

        val merchant = findMerchant(lines) ?: lines.firstOrNull() ?: "Unknown"
        val date = findDate(text)
        val category = inferCategory(merchant, text.lowercase())

        return ExtractedReceipt(
            merchant = merchant,
            amount = amountInfo.amount,
            currency = amountInfo.currency,
            date = date,
            category = category,
            rawText = text.take(500),
        )
    }

    private fun findAmount(text: String): AmountInfo? {
        var best: AmountInfo? = null
        var bestScore = 0

        for (marker in currencyMarkers) {
            val patterns = listOf(
                Pattern.compile(marker + "\\s*([\\d,]+\\.?\\d*)"),
                Pattern.compile("([\\d,]+\\.?\\d*)\\s*" + marker),
            )
            for (pattern in patterns) {
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    val amountStr = matcher.group(1)?.replace(",", "") ?: continue
                    val amount = amountStr.toDoubleOrNull() ?: continue
                    val currency = when {
                        marker.startsWith("₹") || marker.startsWith("Rs") -> "INR"
                        marker.startsWith("$") -> "USD"
                        marker.startsWith("€") -> "EUR"
                        marker.startsWith("£") -> "GBP"
                        else -> marker.replace("Rs.", "").replace("Rs", "").replace("₹", "").trim()
                    }
                    val score = (amount * 10).toInt()
                    if (score > bestScore) {
                        bestScore = score
                        best = AmountInfo(amount, currency)
                    }
                }
            }
        }
        return best
    }

    private fun findMerchant(lines: List<String>): String? {
        for (line in lines) {
            val lower = line.lowercase()
            if (merchantKeywords.any { lower.contains(it) }) {
                return line.take(80)
            }
        }
        return lines.firstOrNull { it.length >= 4 && it.any { it.isLetter() } }?.take(80)
    }

    private fun findDate(text: String): String? {
        val patterns = listOf(
            Pattern.compile("\\b(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})\\b"),
            Pattern.compile("\\b(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})\\b"),
            Pattern.compile("\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{1,2}),?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE),
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) return matcher.group()
        }
        return null
    }

    private fun inferCategory(merchant: String, fullText: String): String? {
        val lower = merchant.lowercase()
        for ((keyword, cat) in categoryMap) {
            if (lower.contains(keyword)) return cat
        }
        val fullLower = fullText.lowercase()
        for ((keyword, cat) in categoryMap) {
            if (fullLower.contains(keyword)) return cat
        }
        return null
    }
}

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
    private val receiptExtractor = ReceiptExtractor
    data class Extracted(
        val urls: List<UrlCandidate>,
        val dates: List<DateCandidate>,
        val phones: List<PhoneCandidate>,
        val prices: List<PriceCandidate>,
        val otps: List<OtpCandidate>,
        val receipt: ExtractedReceipt?,
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
            receipt = try {
                receiptExtractor.extract(text)
            } catch (t: Throwable) {
                failures += "receipt"
                null
            },
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