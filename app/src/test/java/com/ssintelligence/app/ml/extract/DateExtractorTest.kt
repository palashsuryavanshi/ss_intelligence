package com.ssintelligence.app.ml.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Date extraction across supported formats (§16, §36).
 */
class DateExtractorTest {

    private val today = LocalDate.of(2026, 9, 30)
    private val extractor = DateExtractor(today)

    private fun epochDay(text: String): Long? = extractor.extract(text).firstOrNull()?.epochDay

    @Test
    fun `parses day slash month slash year`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("30/09/2026")!!))
    }

    @Test
    fun `parses day dash month dash year`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("30-09-2026")!!))
    }

    @Test
    fun `parses day month name year`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("30 Sep 2026")!!))
    }

    @Test
    fun `parses full month name day and year`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("September 30, 2026")!!))
    }

    @Test
    fun `parses abbreviated month day and year`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("Sep 30, 2026")!!))
    }

    @Test
    fun `parses iso date`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("2026-09-30")!!))
    }

    @Test
    fun `parses compact date`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("20260930")!!))
    }

    @Test
    fun `day first wins when the day cannot be a month`() {
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofEpochDay(epochDay("30/09/2026")!!))
    }

    @Test
    fun `skips an ambiguous numeric date instead of guessing`() {
        // Both 03 and 04 are valid months, so no day/month order can be chosen.
        assertTrue(extractor.extract("Due 03/04 soon").isEmpty())
    }

    @Test
    fun `resolves an unambiguous numeric date without a year`() {
        val result = extractor.extract("Meeting 25/09 at noon")
        assertEquals(LocalDate.of(2026, 9, 25), LocalDate.ofEpochDay(result.first().epochDay))
        assertFalse(result.first().hasYear)
    }

    @Test
    fun `infers a year only for a nearby month`() {
        val result = extractor.extract("Meeting 25 Dec with the team")
        // December is more than two months from September 2026, so the month
        // alone is not treated as a date.
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rejects an impossible calendar date`() {
        assertTrue(extractor.extract("31/02/2026").isEmpty())
    }

    @Test
    fun `does not treat a plain number as a date`() {
        assertTrue(extractor.extract("Order 182739 shipped").isEmpty())
    }

    @Test
    fun `does not treat a phone number as a date`() {
        assertTrue(extractor.extract("Call 9876543210").isEmpty())
    }

    @Test
    fun `retains raw text alongside the normalized date`() {
        val result = extractor.extract("Booked on 30/09/2026")
        assertEquals("30/09/2026", result.first().rawText)
    }

    @Test
    fun `finds several dates in one screenshot`() {
        val result = extractor.extract("From 30/09/2026 to 15/10/2026")
        assertEquals(2, result.size)
    }

    @Test
    fun `returns nothing for blank input`() {
        assertTrue(extractor.extract("").isEmpty())
    }
}
