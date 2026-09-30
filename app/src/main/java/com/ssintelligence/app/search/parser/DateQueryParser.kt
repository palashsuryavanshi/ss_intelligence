package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.DateFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Understands temporal expressions in a search query (§11, §12).
 *
 * Which timestamp is used
 * -----------------------
 * Date filters apply to the screenshot's **`dateAdded`** value — MediaStore's
 * "date added" timestamp in epoch seconds. That is the closest available proxy
 * for "when this screenshot was taken", and unlike `dateModified` it does not
 * move when a file is re-saved or synced.
 *
 * Time zone
 * ---------
 * Day boundaries are computed in the device's local time zone. Nothing is
 * hardcoded to UTC, so "yesterday" means the user's yesterday.
 *
 * Weeks
 * -----
 * "this week" means the current Monday-to-Sunday week (ISO convention), not a
 * rolling seven days. Rolling windows are available separately as
 * "last 7 days".
 *
 * False positives
 * ---------------
 * A bare number is never a date. "20260930", "39999" and "987654" need
 * separators, a month name, or a 19xx/20xx shape before they are read as a
 * date (§44).
 */
class DateQueryParser(
    /** Injectable "now" so relative expressions are deterministic in tests. */
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    private data class Candidate(val filter: DateFilter, val range: IntRange, val priority: Int)

    fun parse(text: String, consumed: List<QuerySpan> = emptyList()): List<ParsedSpan<DateFilter>> {
        if (text.isBlank()) return emptyList()
        val now = today()
        val candidates = mutableListOf<Candidate>()

        // Relative expressions are single fixed phrases, so they get the best
        // (lowest number) priority and always win over a bare month or year.
        fun relative(pattern: String, build: (IntRange) -> Candidate) {
            for (match in Regex(pattern).findAll(text)) candidates += build(match.range)
        }

        relative("""(?i)\b(?:today|tonight|just now)\b""") { range ->
            Candidate(DateFilter(now.toEpochDay(), now.toEpochDay(), "today"), range, 0)
        }
        relative("""(?i)\byesterday\b""") { range ->
            val d = now.minusDays(1)
            Candidate(DateFilter(d.toEpochDay(), d.toEpochDay(), "yesterday"), range, 0)
        }
        relative("""(?i)\b(?:this|current)\s+week\b""") { range ->
            val start = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Candidate(
                DateFilter(start.toEpochDay(), start.plusDays(6).toEpochDay(), "this week"),
                range,
                0,
            )
        }
        relative("""(?i)\b(?:last|previous|past)\s+week\b""") { range ->
            val start = now.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Candidate(
                DateFilter(start.toEpochDay(), start.plusDays(6).toEpochDay(), "last week"),
                range,
                0,
            )
        }
        relative("""(?i)\b(?:this|current)\s+month\b""") { range ->
            Candidate(calendarMonth(now, "this month"), range, 0)
        }
        relative("""(?i)\b(?:last|previous|past)\s+month\b""") { range ->
            Candidate(calendarMonth(now.minusMonths(1), "last month"), range, 0)
        }
        relative("""(?i)\b(?:this|current)\s+year\b""") { range ->
            Candidate(calendarYear(now.year, "this year"), range, 0)
        }
        relative("""(?i)\b(?:last|previous|past)\s+year\b""") { range ->
            Candidate(calendarYear(now.year - 1, "last year"), range, 0)
        }

        // Rolling windows: "last 7 days", "past 3 weeks", "last 2 months".
        for (match in Regex(
            """(?i)\b(?:last|past|previous)\s+(\d{1,3})\s+(day|days|week|weeks|month|months|year|years)\b""",
        ).findAll(text)) {
            val amount = match.groupValues[1].toIntOrNull() ?: continue
            if (amount !in 1..MAX_WINDOW) continue
            val unit = match.groupValues[2].lowercase().removeSuffix("s")
            // A rolling window of N units ending today: "last 7 days" is the
            // seven most recent days including today, "past 2 weeks" the
            // fourteen most recent.
            val start = when (unit) {
                "day" -> now.minusDays((amount - 1).toLong())
                "week" -> now.minusWeeks(amount.toLong()).plusDays(1)
                "month" -> now.minusMonths(amount.toLong()).plusDays(1)
                "year" -> now.minusYears(amount.toLong()).plusDays(1)
                else -> continue
            }
            candidates += Candidate(
                DateFilter(start.toEpochDay(), now.toEpochDay(), "last $amount ${unit}s"),
                match.range,
                1,
            )
        }

        // ISO: 2026-09-30
        for (match in Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""").findAll(text)) {
            val date = parseYmd(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                ?: continue
            candidates += Candidate(dayOf(date), match.range, 1)
        }

        // Slash dates. The convention matches the OCR extractor exactly: a first
        // field above 12 can only be a day, which pins the order down; when both
        // fields could be a month the month-first reading is used, so
        // "05/06/2026" is 6 May here just as it is in the index.
        for (match in Regex("""\b(\d{1,2})[/](\d{1,2})[/](\d{2,4})\b""").findAll(text)) {
            val first = match.groupValues[1].toIntOrNull() ?: continue
            val second = match.groupValues[2].toIntOrNull() ?: continue
            val rawYear = match.groupValues[3].toIntOrNull() ?: continue
            val year = if (rawYear < 100) 2000 + rawYear else rawYear
            val (month, day) = if (first > 12) second to first else first to second
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            candidates += Candidate(dayOf(date), match.range, 1)
        }

        // "before January", "until 2026"
        for (match in Regex(
            """(?i)\b(?:before|prior to|earlier than|until|till|up to)\s+($MONTH)\b(?:\s+(\d{4}))?""",
        ).findAll(text)) {
            val month = monthNumber(match.groupValues[1]) ?: continue
            val year = match.groupValues[2].toIntOrNull() ?: inferYear(month, now)
            val startOfMonth = LocalDate.of(year, month, 1)
            candidates += Candidate(
                DateFilter(
                    startEpochDay = MIN_EPOCH_DAY,
                    endEpochDayInclusive = startOfMonth.minusDays(1).toEpochDay(),
                    label = "before ${startOfMonth.format(HUMAN_MONTH)}",
                ),
                match.range,
                1,
            )
        }
        for (match in Regex("""(?i)\b(?:before|prior to|earlier than|until|till|up to)\s+((?:19|20)\d{2})\b""")
            .findAll(text)) {
            val year = match.groupValues[1].toIntOrNull() ?: continue
            candidates += Candidate(
                DateFilter(MIN_EPOCH_DAY, LocalDate.of(year, 1, 1).minusDays(1).toEpochDay(), "before $year"),
                match.range,
                1,
            )
        }

        // "after June", "since 2026"
        for (match in Regex("""(?i)\b(?:after|since)\s+($MONTH)\b(?:\s+(\d{4}))?""").findAll(text)) {
            val month = monthNumber(match.groupValues[1]) ?: continue
            val year = match.groupValues[2].toIntOrNull() ?: inferYear(month, now)
            val endOfMonth = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth())
            candidates += Candidate(
                DateFilter(
                    startEpochDay = endOfMonth.plusDays(1).toEpochDay(),
                    endEpochDayInclusive = MAX_EPOCH_DAY,
                    label = "after ${endOfMonth.format(HUMAN_MONTH)}",
                ),
                match.range,
                1,
            )
        }
        for (match in Regex("""(?i)\b(?:after|since)\s+((?:19|20)\d{2})\b""").findAll(text)) {
            val year = match.groupValues[1].toIntOrNull() ?: continue
            candidates += Candidate(
                DateFilter(
                    LocalDate.of(year, 12, 31).plusDays(1).toEpochDay(),
                    MAX_EPOCH_DAY,
                    "after $year",
                ),
                match.range,
                1,
            )
        }

        // "30 September 2026"
        for (match in Regex(
            """(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s+($MONTH)\b(?:\s+(\d{4}))?""",
        ).findAll(text)) {
            val day = match.groupValues[1].toIntOrNull() ?: continue
            val month = monthNumber(match.groupValues[2]) ?: continue
            val year = match.groupValues[3].toIntOrNull() ?: inferYear(month, now, day)
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            candidates += Candidate(dayOf(date), match.range, 2)
        }

        // "September 28, 2026"
        for (match in Regex(
            """(?i)\b($MONTH)\s+(\d{1,2})(?:st|nd|rd|th)?,?\s*(\d{4})?\b""",
        ).findAll(text)) {
            val month = monthNumber(match.groupValues[1]) ?: continue
            val day = match.groupValues[2].toIntOrNull() ?: continue
            val year = match.groupValues[3].toIntOrNull() ?: inferYear(month, now, day)
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            candidates += Candidate(dayOf(date), match.range, 2)
        }

        // Bare month, optionally qualified: "in September", "from Sep 2026".
        for (match in Regex(
            """(?i)\b(?:in|from|during|of)?\s*($MONTH)\b(?:\s+(\d{4}))?""",
        ).findAll(text)) {
            val raw = match.groupValues[1]
            val month = monthNumber(raw) ?: continue
            val explicitYear = match.groupValues[2].toIntOrNull()
            // "May" is also a modal verb. Without a day number or a year, a bare
            // "may" is far more likely to be English than a month.
            if (raw.equals("may", ignoreCase = true) && explicitYear == null) continue
            val year = explicitYear ?: inferYear(month, now)
            candidates += Candidate(calendarMonth(LocalDate.of(year, month, 1), null), match.range, 3)
        }

        // Bare year: "in 2026", "from 2026". Requires a preposition, because a
        // bare 19xx/20xx is far more often a model number ("iPhone 2026") than a
        // year, and a 19xx/20xx shape is all that separates this from the
        // non-date "20260930".
        for (match in Regex("""(?i)\b(?:in|from|during|of)\s*((?:19|20)\d{2})\b""").findAll(text)) {
            val year = match.groupValues[1].toIntOrNull() ?: continue
            candidates += Candidate(calendarYear(year, year.toString()), match.range, 3)
        }

        // Resolve overlaps: the earlier (higher priority) pattern wins, and
        // within a priority the leftmost match wins. A "before January" match
        // therefore consumes "January" so it is not also read as a bare month.
        val accepted = mutableListOf<Candidate>()
        for (candidate in candidates.sortedWith(compareBy({ it.priority }, { it.range.first }))) {
            if (overlapsAny(candidate.range, consumed)) continue
            val clashes = accepted.any { previous ->
                candidate.range.first <= previous.range.last && previous.range.first <= candidate.range.last
            }
            if (clashes) continue
            accepted += candidate
        }

        return accepted
            .sortedBy { it.range.first }
            .map { ParsedSpan(it.filter, QuerySpan(it.range.first, it.range.last + 1)) }
    }

    // ------------------------------------------------------------- helpers

    private fun monthNumber(name: String): Int? = MONTHS[name.lowercase()]

    private fun dayOf(date: LocalDate) = DateFilter(
        startEpochDay = date.toEpochDay(),
        endEpochDayInclusive = date.toEpochDay(),
        label = date.format(HUMAN_DATE),
    )

    private fun calendarMonth(anyDayInMonth: LocalDate, label: String?): DateFilter {
        val first = anyDayInMonth.withDayOfMonth(1)
        val last = first.with(TemporalAdjusters.lastDayOfMonth())
        return DateFilter(
            startEpochDay = first.toEpochDay(),
            endEpochDayInclusive = last.toEpochDay(),
            label = label ?: last.format(HUMAN_MONTH),
        )
    }

    private fun calendarYear(year: Int, label: String) = DateFilter(
        startEpochDay = LocalDate.of(year, 1, 1).toEpochDay(),
        endEpochDayInclusive = LocalDate.of(year, 12, 31).toEpochDay(),
        label = label,
    )

    /**
     * Picks a year for a month the user did not qualify.
     *
     * A bare "September" means the most recent September. If that month is
     * still ahead of today ("December" in October) the previous year is used,
     * so a query never points at a range that has not happened yet.
     */
    private fun inferYear(month: Int, now: LocalDate, day: Int = 1): Int {
        val candidate = runCatching { LocalDate.of(now.year, month, day.coerceIn(1, 28)) }
            .getOrNull()
            ?: return now.year
        return if (candidate.isAfter(now)) now.year - 1 else now.year
    }

    private fun parseYmd(year: String, month: String, day: String): LocalDate? {
        val y = year.toIntOrNull() ?: return null
        val m = month.toIntOrNull() ?: return null
        val d = day.toIntOrNull() ?: return null
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    private fun overlapsAny(range: IntRange, spans: List<QuerySpan>): Boolean =
        spans.any { it.start <= range.last && range.first < it.end }

    private companion object {
        const val MAX_WINDOW = 3650

        /**
         * Open-ended ranges use 1970 and year 9999 as sentinels rather than
         * unbounded numbers, so the converted millisecond values stay inside
         * SQLite's INTEGER range and remain comparable.
         */
        const val MIN_EPOCH_DAY = 0L
        const val MAX_EPOCH_DAY = 2_932_896L

        val MONTHS: Map<String, Int> = mapOf(
            "january" to 1, "jan" to 1,
            "february" to 2, "feb" to 2,
            "march" to 3, "mar" to 3,
            "april" to 4, "apr" to 4,
            "may" to 5,
            "june" to 6, "jun" to 6,
            "july" to 7, "jul" to 7,
            "august" to 8, "aug" to 8,
            "september" to 9, "sep" to 9, "sept" to 9,
            "october" to 10, "oct" to 10,
            "november" to 11, "nov" to 11,
            "december" to 12, "dec" to 12,
        )

        /** Long names first: regex alternation is ordered, so "sep" cannot win. */
        const val MONTH = "(?:january|february|september|december|october|november|" +
            "march|april|august|june|july|jan|feb|sept|mar|apr|jun|jul|aug|sep|oct|nov|dec|may)"

        val HUMAN_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy")
        val HUMAN_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")
    }
}
