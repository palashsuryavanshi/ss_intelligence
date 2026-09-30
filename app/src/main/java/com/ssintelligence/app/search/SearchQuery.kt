package com.ssintelligence.app.search

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Structured representation of a user's search query (§5).
 *
 * The raw natural-language string is **never** handed to SQLite. It is parsed
 * once by [QueryParser] into this model, and the database only ever sees the
 * derived parts: FTS terms, a price range, a date range, a domain, a phone
 * number. Keeping the two apart is what makes the queries indexable (§17) and
 * makes the ranking explainable (§27).
 *
 * Every field is optional. A query with only [textTerms] behaves like plain
 * keyword search; a query with only [prices] behaves like a price filter.
 */
data class SearchQuery(
    /** Exactly what the user typed. Kept for display; never used in SQL. */
    val originalQuery: String,
    val intent: SearchIntent = SearchIntent.SEARCH,
    /** Individual FTS terms, lowercased, AND-ed together. */
    val textTerms: List<String> = emptyList(),
    /** Multi-word runs kept intact, e.g. `"Pixel 9a"`. */
    val phrases: List<String> = emptyList(),
    val prices: List<PriceFilter> = emptyList(),
    val dateFilters: List<DateFilter> = emptyList(),
    /** Normalized lowercase hosts, e.g. `amazon.in`. */
    val urls: List<String> = emptyList(),
    /** E.164-style normalized phone numbers, e.g. `+919876543210`. */
    val phoneNumbers: List<String> = emptyList(),
    /** 4–8 digit codes, matched but never displayed (§15). */
    val otpCodes: List<String> = emptyList(),
    val contentTypes: Set<ContentType> = emptySet(),
    val timeRange: TimeRange? = null,
    val sortMode: SortMode = SortMode.RELEVANCE,
) {
    /** True when nothing in the query narrows the result set. */
    val isEmpty: Boolean
        get() = textTerms.isEmpty() && prices.isEmpty() && dateFilters.isEmpty() &&
            urls.isEmpty() && phoneNumbers.isEmpty() && otpCodes.isEmpty() &&
            contentTypes.isEmpty() && timeRange == null

    /** Terms plus phrase words — everything the FTS index should see. */
    val ftsTerms: List<String>
        get() = buildList {
            addAll(textTerms)
            for (phrase in phrases) phrase.split(' ').forEach { add(it) }
        }.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()

    /**
     * A short, user-facing description of what was understood.
     *
     * Intentionally avoids internal vocabulary such as `intent=FIND` or
     * `priceFilter=EXACT` (§25) — it reads as plain English.
     */
    fun describe(): String {
        val parts = buildList {
            addAll(phrases)
            addAll(textTerms.filterNot { term -> phrases.any { phrase -> phrase.split(' ').contains(term) } })
            addAll(prices.map { it.display() })
            addAll(dateFilters.map { it.label })
            addAll(urls)
            addAll(phoneNumbers)
            if (otpCodes.isNotEmpty()) add("one-time code")
            if (ContentType.DUPLICATES in contentTypes) add("duplicates")
        }
        return if (parts.isEmpty()) "everything" else parts.distinct().joinToString(" · ")
    }
}

/** How results are ordered (§31). */
enum class SortMode { RELEVANCE, NEWEST, OLDEST }

/**
 * Structural "this screenshot contains…" classification (§30).
 *
 * [filterName] is the token the SQL layer compares against, so the DAO stays a
 * single parameterized query instead of one query per filter combination.
 */
enum class ContentType(val filterName: String) {
    URLS("URLS"),
    PRICES("PRICES"),
    DATES("DATES"),
    PHONES("PHONES"),
    OTPS("OTPS"),
    DUPLICATES("DUPLICATES"),
}

/**
 * A price constraint (§9, §10).
 *
 * Currencies are normalized to ISO-4217 codes but **never converted**: `400 USD`
 * and `₹400` stay different currencies, so a cross-currency query legitimately
 * finds nothing rather than silently comparing unlike amounts.
 */
sealed interface PriceFilter {

    /** ISO-4217 code, or null when the query did not name a currency. */
    val currency: String?

    /** Inclusive lower bound in major currency units. */
    val min: Double

    /** Inclusive upper bound in major currency units. */
    val max: Double

    /** Renders back to something a person would have typed. */
    fun display(): String

    /** A price the screenshot actually contains satisfying this filter. */
    fun contains(amount: Double, currency: String): Boolean

    /** How close an actual price is, 1.0 being a perfect match. */
    fun closeness(amount: Double, currency: String): Double

    data class Exact(override val currency: String?, val amount: Double) : PriceFilter {
        override val min: Double get() = amount
        override val max: Double get() = amount
        override fun display(): String = Currency.format(currency, amount)
        override fun contains(amount: Double, currency: String) =
            currencyMatches(currency) && amount == this.amount

        override fun closeness(amount: Double, currency: String): Double = when {
            // A different currency is not a near miss, it is a non-match: no
            // conversion happens in Phase 2, so `$1,299` cannot stand in for
            // `₹1,299`.
            !currencyMatches(currency) -> 0.0
            amount == this.amount -> 1.0
            else -> 0.0
        }
    }

    data class AtMost(override val currency: String?, val amount: Double) : PriceFilter {
        override val min: Double get() = 0.0
        override val max: Double get() = amount
        override fun display(): String = "up to ${Currency.format(currency, amount)}"
        override fun contains(amount: Double, currency: String) =
            currencyMatches(currency) && amount <= this.amount

        override fun closeness(amount: Double, currency: String): Double = when {
            !currencyMatches(currency) -> 0.0
            amount <= this.amount -> 1.0
            else -> 0.0
        }
    }

    data class AtLeast(override val currency: String?, val amount: Double) : PriceFilter {
        override val min: Double get() = amount
        override val max: Double get() = Double.MAX_VALUE
        override fun display(): String = "from ${Currency.format(currency, amount)}"
        override fun contains(amount: Double, currency: String) =
            currencyMatches(currency) && amount >= this.amount

        override fun closeness(amount: Double, currency: String): Double = when {
            !currencyMatches(currency) -> 0.0
            amount >= this.amount -> 1.0
            else -> 0.0
        }
    }

    data class Range(
        override val currency: String?,
        override val min: Double,
        override val max: Double,
    ) : PriceFilter {
        override fun display(): String =
            "${Currency.format(currency, min)} – ${Currency.format(currency, max)}"

        override fun contains(amount: Double, currency: String) =
            currencyMatches(currency) && amount in min..max

        /** 1.0 inside the range, decaying linearly to 0.0 at 2x the width out. */
        override fun closeness(amount: Double, currency: String): Double {
            if (!currencyMatches(currency)) return 0.0
            if (amount in min..max) return 1.0
            val span = (max - min).coerceAtLeast(1.0)
            val distance = if (amount < min) min - amount else amount - max
            return (1.0 - distance / (2 * span)).coerceIn(0.0, 1.0)
        }
    }
}

/** A null currency means "any currency", which is how unlabelled numbers parse. */
fun PriceFilter.currencyMatches(other: String): Boolean = currency == null || currency == other

/**
 * A [PriceFilter] flattened to the three values the SQL layer needs.
 *
 * SQL cannot match a sealed hierarchy, and one band per query is all the
 * database can express anyway.
 */
data class PriceBand(
    val currency: String?,
    val min: Double,
    val max: Double,
)

fun PriceFilter.toBand(): PriceBand = PriceBand(
    currency = currency,
    min = min,
    max = if (max == Double.MAX_VALUE) Double.MAX_VALUE else max,
)

/** ISO-4217 codes and the symbols that map to them. */
object Currency {
    const val INR = "INR"
    const val USD = "USD"
    const val EUR = "EUR"
    const val GBP = "GBP"

    private val symbols = mapOf(
        "₹" to INR, "rs" to INR, "inr" to INR, "rupee" to INR, "rupees" to INR,
        "$" to USD, "usd" to USD, "dollar" to USD, "dollars" to USD,
        "€" to EUR, "eur" to EUR, "euro" to EUR, "euros" to EUR,
        "£" to GBP, "gbp" to GBP, "pound" to GBP, "pounds" to GBP,
    )

    /** Resolves a symbol, code or word to an ISO-4217 code, or null. */
    fun resolve(marker: String): String? = symbols[marker.trim().lowercase()]

    /** The conventional symbol for a known currency, or null. */
    fun symbol(currency: String?): String? = when (currency) {
        INR -> "₹"
        USD -> "$"
        EUR -> "€"
        GBP -> "£"
        else -> null
    }

    /**
     * Renders an amount for display, restoring the conventional symbol for
     * known currencies and falling back to a plain number for unknown ones.
     *
     * Digit grouping is Indian (`1,00,000`) because that is how these amounts
     * are written in the screenshots the app reads and in the queries the user
     * types. Echoing the user's own notation back is less confusing than
     * switching conventions on them.
     */
    fun format(currency: String?, amount: Double): String {
        val digits = if (amount % 1.0 == 0.0) {
            amount.toLong().toString()
        } else {
            "%.2f".format(amount)
        }
        val grouped = groupIndianDigits(digits)
        return when (currency) {
            INR -> "₹$grouped"
            USD -> "$$grouped"
            EUR -> "€$grouped"
            GBP -> "£$grouped"
            null -> grouped
            else -> "$grouped $currency"
        }
    }

    /**
     * Inserts Indian grouping separators: the last three digits stay together
     * and everything before them groups in twos (`1234567` → `12,34,567`).
     */
    internal fun groupIndianDigits(digits: String): String {
        val dot = digits.indexOf('.')
        val whole = if (dot < 0) digits else digits.substring(0, dot)
        val fraction = if (dot < 0) "" else digits.substring(dot)
        if (whole.length <= 3) return digits
        val head = whole.dropLast(3)
        val tail = whole.takeLast(3)
        // Group the head in twos from the right, then put the groups back into
        // reading order: "100" -> "1,00", "1234" -> "12,34".
        val groups = head.reversed().chunked(2).asReversed()
        return groups.joinToString(",", postfix = ",$tail") { it.reversed() } + fraction
    }
}

/** A time window derived from a temporal expression (§11, §12). */
data class TimeRange(
    /** Inclusive, epoch millis in the device's local time zone. */
    val startMillis: Long,
    /** Exclusive, epoch millis. */
    val endMillis: Long,
) {
    fun contains(epochSeconds: Long): Boolean {
        val millis = epochSeconds * 1000
        return millis in startMillis until endMillis
    }

    /**
     * The same window in epoch **seconds**, which is the unit MediaStore and the
     * `date_added` column use. Comparing in seconds keeps the date index usable;
     * multiplying the column to milliseconds would force a scan.
     */
    fun toEpochSeconds(): LongRange = (startMillis / 1000)..(endMillis / 1000 - 1)

    companion object {
        /** Local-midnight-to-local-midnight window covering one calendar day. */
        fun day(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val start = LocalDateTime.of(date, LocalTime.MIN).atZone(zone)
            val end = date.plusDays(1).atStartOfDay(zone)
            return TimeRange(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
        }

        fun days(start: LocalDate, endInclusive: LocalDate, zone: ZoneId = ZoneId.systemDefault()): TimeRange =
            TimeRange(
                startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli(),
                endMillis = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )

        fun fromEpochDays(startEpochDay: Long, endEpochDayInclusive: Long, zone: ZoneId = ZoneId.systemDefault()): TimeRange =
            days(LocalDate.ofEpochDay(startEpochDay), LocalDate.ofEpochDay(endEpochDayInclusive), zone)

        fun of(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val date = instant.atZone(zone).toLocalDate()
            return day(date, zone)
        }
    }
}

/**
 * One recognized temporal expression.
 *
 * [start] is inclusive and [end] is inclusive, both as epoch days, so a range is
 * easy to reason about; the engine converts to millis for SQL.
 */
data class DateFilter(
    val startEpochDay: Long,
    val endEpochDayInclusive: Long,
    /** Plain-English description, e.g. "September 2026". */
    val label: String,
) {
    fun toTimeRange(zone: ZoneId = ZoneId.systemDefault()): TimeRange =
        TimeRange.fromEpochDays(startEpochDay, endEpochDayInclusive, zone)
}
