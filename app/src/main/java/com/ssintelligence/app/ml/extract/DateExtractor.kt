package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.DateCandidate
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date detection (§16).
 *
 * Supported shapes: numeric (`30/09/2026`, `30-09-2026`, `2026-09-30`,
 * `20260930`) and month-name forms (`30 Sep 2026`, `Sep 30, 2026`).
 *
 * Design rules:
 * - A numeric date whose day and month are both ≤ 12 is treated as ambiguous
 *   and skipped rather than guessed.
 * - Day-first wins when the first component is > 12 (only possible as a day).
 * - Dates without a year are only inferred when the month is within ±2 months
 *   of the current month, and are flagged via [DateCandidate.hasYear] so the
 *   UI can fall back to showing the raw OCR text instead of asserting a date.
 */
class DateExtractor(
    private val today: LocalDate = LocalDate.now(),
) {
    private val currentYear = today.year
    private val currentMonth = today.monthValue

    private val monthNames = "January|February|March|April|May|June|July|August|September|October|November|December" +
        "|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec"

    private val dayFirstNumeric = Regex("""\b(\d{1,2})\s*([/.\-])\s*(\d{1,2})\2\s*(\d{2,4})\b""")
    /**
     * Day/month with no year at all, e.g. "Due 25/09". The separator is *not*
     * repeated here, so trailing-digit and trailing-separator guards prevent
     * this from matching the year-bearing form (which is handled above and
     * claims its text range first).
     */
    private val dayFirstNumericNoYear =
        Regex("""(?<![\d/.\-])(\d{1,2})\s*([/.\-])\s*(\d{1,2})(?![\d/.\-])""")
    private val yearFirstNumeric = Regex("""\b(\d{4})\s*([/.\-])\s*(\d{1,2})\2\s*(\d{1,2})\b""")
    private val yearFirstCompact = Regex("""\b(\d{4})(\d{2})(\d{2})\b""")
    private val dayMonthName = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?[\s\-]+($monthNames)\.?[\s\-]+(\d{2,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val monthNameDay = Regex(
        """\b($monthNames)\.?\s+(\d{1,2})(?:st|nd|rd|th)?[\s,\-]+(\d{2,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val dayMonthNameNoYear = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?[\s\-]+($monthNames)\.?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val monthNameDayNoYear = Regex(
        """\b($monthNames)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: String): List<DateCandidate> {
        if (text.isBlank()) return emptyList()
        val results = LinkedHashMap<String, DateCandidate>()
        // Ranges already claimed by an explicit-year match, so the year-less
        // patterns never emit a second, weaker candidate for the same date.
        val claimed = mutableListOf<IntRange>()

        fun add(candidate: DateCandidate, range: IntRange) {
            results.putIfAbsent(candidate.rawText, candidate)
            claimed += range
        }

        for (m in yearFirstNumeric.findAll(text)) {
            val c = candidate(
                raw = m.value, year = m.groupValues[1].toInt(),
                month = m.groupValues[3].toInt(), day = m.groupValues[4].toInt(), hasYear = true,
            ) ?: continue
            add(c, m.range)
        }
        for (m in yearFirstCompact.findAll(text)) {
            val c = candidate(
                raw = m.value, year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(), day = m.groupValues[3].toInt(), hasYear = true,
            ) ?: continue
            add(c, m.range)
        }
        for (m in dayFirstNumeric.findAll(text)) {
            val first = m.groupValues[1].toInt()
            val second = m.groupValues[3].toInt()
            val yearRaw = m.groupValues[4]
            val hasYear = yearRaw.length == 4
            val year = normalizeYear(yearRaw.toInt()) ?: continue
            if (!hasYear && first <= 12 && second <= 12) continue // ambiguous
            // Values above 12 can only be a day, which pins down day-first order.
            val (month, day) = if (first > 12) second to first else first to second
            val c = candidate(m.value, year, month, day, hasYear) ?: continue
            add(c, m.range)
        }
        for (m in dayMonthName.findAll(text)) {
            val month = monthNumber(m.groupValues[2]) ?: continue
            val yearRaw = m.groupValues[3]
            val hasYear = yearRaw.length == 4
            val year = normalizeYear(yearRaw.toInt()) ?: continue
            val c = candidate(m.value, year, month, m.groupValues[1].toInt(), hasYear) ?: continue
            add(c, m.range)
        }
        for (m in monthNameDay.findAll(text)) {
            val month = monthNumber(m.groupValues[1]) ?: continue
            val yearRaw = m.groupValues[3]
            val hasYear = yearRaw.length == 4
            val year = normalizeYear(yearRaw.toInt()) ?: continue
            val c = candidate(m.value, year, month, m.groupValues[2].toInt(), hasYear) ?: continue
            add(c, m.range)
        }
        for (m in dayFirstNumericNoYear.findAll(text)) {
            if (overlaps(m.range, claimed)) continue
            val first = m.groupValues[1].toInt()
            val second = m.groupValues[3].toInt()
            // Still ambiguous when both parts could be a month.
            if (first <= 12 && second <= 12) continue
            val (month, day) = if (first > 12) second to first else first to second
            if (monthDistance(month) > 2) continue
            val c = candidate(m.value, currentYear, month, day, hasYear = false) ?: continue
            add(c, m.range)
        }
        for (m in dayMonthNameNoYear.findAll(text)) {
            if (overlaps(m.range, claimed)) continue
            val month = monthNumber(m.groupValues[2]) ?: continue
            if (monthDistance(month) > 2) continue
            val c = candidate(m.value, currentYear, month, m.groupValues[1].toInt(), hasYear = false)
                ?: continue
            add(c, m.range)
        }
        for (m in monthNameDayNoYear.findAll(text)) {
            if (overlaps(m.range, claimed)) continue
            val month = monthNumber(m.groupValues[1]) ?: continue
            if (monthDistance(month) > 2) continue
            val c = candidate(m.value, currentYear, month, m.groupValues[2].toInt(), hasYear = false)
                ?: continue
            add(c, m.range)
        }
        return results.values.toList()
    }

    private fun overlaps(range: IntRange, claimed: List<IntRange>): Boolean =
        claimed.any { range.first <= it.last && it.first <= range.last }

    private fun monthDistance(month: Int): Int {
        val direct = Math.abs(month - currentMonth)
        return minOf(direct, 12 - direct)
    }

    private fun candidate(
        raw: String,
        year: Int,
        month: Int,
        day: Int,
        hasYear: Boolean,
    ): DateCandidate? {
        if (year < 1990 || year > 2100) return null
        if (month !in 1..12 || day !in 1..31) return null
        val date = try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            return null
        }
        return DateCandidate(rawText = raw.trim(), epochDay = date.toEpochDay(), hasYear = hasYear)
    }

    private fun normalizeYear(raw: Int): Int? = when {
        raw in 1990..2099 -> raw
        raw in 90..99 -> 1900 + raw
        raw in 0..49 -> 2000 + raw
        else -> null
    }

    private fun monthNumber(name: String): Int? {
        val clean = name.trim().removeSuffix(".").lowercase(Locale.ROOT)
        if (clean.length < 3) return null
        for (m in 1..12) {
            val full = Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ROOT)
            val short = Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                .lowercase(Locale.ROOT)
                .removeSuffix(".")
            if (clean == full || clean == short) return m
        }
        // Compact forms and common OCR misspellings ("Sept").
        return mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10,
            "nov" to 11, "dec" to 12,
        )[clean]
    }
}
