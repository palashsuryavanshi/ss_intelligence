package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.DateFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Temporal understanding (§11, §12) and the date false-positive guards (§44).
 *
 * A fixed "today" is injected so relative expressions are deterministic.
 */
class DateQueryParserTest {

    /** 30 September 2026, a Wednesday. */
    private val today = LocalDate.of(2026, 9, 30)
    private val parser = DateQueryParser(today = { today })

    private fun single(query: String): DateFilter {
        val parsed = parser.parse(query)
        assertEquals("expected exactly one date in \"$query\"", 1, parsed.size)
        return parsed.single().value
    }

    private fun none(query: String) {
        assertTrue(
            "expected no date in \"$query\" but got ${parser.parse(query).map { it.value.label }}",
            parser.parse(query).isEmpty(),
        )
    }

    private fun dates(start: String, end: String): Pair<Long, Long> =
        LocalDate.parse(start).toEpochDay() to LocalDate.parse(end).toEpochDay()

    // -------------------------------------------------------------- relative

    @Test
    fun `today and yesterday use the injected local date`() {
        assertEquals("today", single("screenshots from today").label)
        assertEquals("yesterday", single("screenshots from yesterday").label)
        assertEquals(
            today.minusDays(1).toEpochDay(),
            single("yesterday").startEpochDay,
        )
    }

    @Test
    fun `this week is the Monday to Sunday week`() {
        val filter = single("screenshots from this week")
        // 30 September 2026 is a Wednesday; the week starts Monday the 28th.
        assertEquals("2026-09-28".let { LocalDate.parse(it).toEpochDay() }, filter.startEpochDay)
        assertEquals("2026-10-04".let { LocalDate.parse(it).toEpochDay() }, filter.endEpochDayInclusive)
    }

    @Test
    fun `last week is the previous Monday to Sunday week`() {
        val filter = single("screenshots from last week")
        assertEquals(LocalDate.parse("2026-09-21").toEpochDay(), filter.startEpochDay)
        assertEquals(LocalDate.parse("2026-09-27").toEpochDay(), filter.endEpochDayInclusive)
    }

    @Test
    fun `this and last month are calendar months`() {
        assertEquals(
            dates("2026-09-01", "2026-09-30"),
            single("screenshots from this month").let { it.startEpochDay to it.endEpochDayInclusive },
        )
        assertEquals(
            dates("2026-08-01", "2026-08-31"),
            single("screenshots from last month").let { it.startEpochDay to it.endEpochDayInclusive },
        )
    }

    @Test
    fun `this and last year are calendar years`() {
        assertEquals(
            dates("2026-01-01", "2026-12-31"),
            single("screenshots from this year").let { it.startEpochDay to it.endEpochDayInclusive },
        )
        assertEquals(
            dates("2025-01-01", "2025-12-31"),
            single("screenshots from last year").let { it.startEpochDay to it.endEpochDayInclusive },
        )
    }

    @Test
    fun `a rolling window of N days is inclusive of today`() {
        val filter = single("screenshots from the last 7 days")
        assertEquals(today.minusDays(6).toEpochDay(), filter.startEpochDay)
        assertEquals(today.toEpochDay(), filter.endEpochDayInclusive)
        assertEquals("last 7 days", filter.label)
    }

    @Test
    fun `rolling windows accept weeks and months`() {
        // A rolling window of N units ending today.
        assertEquals(today.minusDays(13).toEpochDay(), single("past 2 weeks").startEpochDay)
        assertEquals(
            today.minusMonths(2).plusDays(1).toEpochDay(),
            single("last 2 months").startEpochDay,
        )
    }

    // ----------------------------------------------------------------- months

    @Test
    fun `a bare month resolves to the most recent one`() {
        val filter = single("screenshots from September")
        assertEquals("September 2026", filter.label)
        assertEquals(
            dates("2026-09-01", "2026-09-30"),
            filter.startEpochDay to filter.endEpochDayInclusive,
        )
    }

    @Test
    fun `a bare month that has not happened yet resolves to last year`() {
        // "today" is September 2026, so a query for December means December 2025.
        val filter = single("screenshots from December")
        assertEquals("December 2025", filter.label)
    }

    @Test
    fun `a month and year`() {
        val filter = single("screenshots from September 2026")
        assertEquals(
            dates("2026-09-01", "2026-09-30"),
            filter.startEpochDay to filter.endEpochDayInclusive,
        )
    }

    @Test
    fun `month abbreviations work`() {
        assertEquals("September 2026", single("screenshots in Sep 2026").label)
        assertEquals("January 2026", single("screenshots in Jan 2026").label)
    }

    // ------------------------------------------------------------------- days

    @Test
    fun `day and month name`() {
        val filter = single("screenshots from 28 September")
        assertEquals(
            dates("2026-09-28", "2026-09-28"),
            filter.startEpochDay to filter.endEpochDayInclusive,
        )
    }

    @Test
    fun `month and day name`() {
        val filter = single("screenshots from September 28, 2026")
        assertEquals(
            dates("2026-09-28", "2026-09-28"),
            filter.startEpochDay to filter.endEpochDayInclusive,
        )
    }

    @Test
    fun `ISO dates`() {
        val filter = single("screenshots from 2026-09-30")
        assertEquals(
            dates("2026-09-30", "2026-09-30"),
            filter.startEpochDay to filter.endEpochDayInclusive,
        )
    }

    @Test
    fun `slash dates read day first only when the first field cannot be a month`() {
        assertEquals(
            dates("2026-12-31", "2026-12-31"),
            single("screenshots from 31/12/2026").let { it.startEpochDay to it.endEpochDayInclusive },
        )
        // Both fields could be a month, so the month-first reading wins — the
        // same convention the OCR extractor applies to indexed dates.
        assertEquals(
            dates("2026-05-06", "2026-05-06"),
            single("screenshots from 05/06/2026").let { it.startEpochDay to it.endEpochDayInclusive },
        )
    }

    @Test
    fun `years need a preposition`() {
        assertEquals("2026", single("screenshots from 2026").label)
        assertEquals("2024", single("screenshots in 2024").label)
    }

    // ------------------------------------------------------- before and after

    @Test
    fun `before a month ends on the last day of the previous month`() {
        val filter = single("screenshots before January 2026")
        assertEquals(LocalDate.parse("2025-12-31").toEpochDay(), filter.endEpochDayInclusive)
    }

    @Test
    fun `after a month starts on the first day of the next month`() {
        val filter = single("screenshots after June 2026")
        assertEquals(LocalDate.parse("2026-07-01").toEpochDay(), filter.startEpochDay)
    }

    @Test
    fun `before January is not also read as a bare January`() {
        val parsed = parser.parse("screenshots before January")
        assertEquals(1, parsed.size)
        assertEquals("before January 2026", parsed.single().value.label)
        assertEquals(
            LocalDate.parse("2025-12-31").toEpochDay(),
            parsed.single().value.endEpochDayInclusive,
        )
    }

    // ------------------------------------------------------- false positives

    @Test
    fun `a compact eight digit number is not a date`() {
        none("20260930")
        none("find the screenshot 20260930")
    }

    @Test
    fun `an order number is not a date`() {
        none("Order 39999")
        none("order id 39999")
    }

    @Test
    fun `a phone number is not a date`() {
        none("find the screenshot containing 9876543210")
    }

    @Test
    fun `a one-time code is not a date`() {
        none("otp 483921")
    }

    @Test
    fun `a price is not a date`() {
        none("Pixel 9a for ₹39,999")
        none("below ₹40,000")
    }

    @Test
    fun `a bare four digit year without a preposition stays a search term`() {
        // A year shape alone is not enough: "iPhone 2026" is a model number.
        none("iPhone 2026")
        none("Pixel 2026 Pro")
    }

    @Test
    fun `may is only a month with a day number or a year`() {
        val withDay = single("screenshots from 15 May 2026")
        assertEquals("15 May 2026", withDay.label)
        none("I may have taken this screenshot")
        none("show me what I may have bought")
    }

    @Test
    fun `version numbers are not dates`() {
        none("app version 3.5")
        none("android 12 update")
    }
}
