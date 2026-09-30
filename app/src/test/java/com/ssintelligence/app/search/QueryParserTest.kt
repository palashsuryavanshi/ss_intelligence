package com.ssintelligence.app.search

import com.ssintelligence.app.search.parser.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The parser as a whole: what a person means, not what a machine said (§2, §22).
 *
 * A fixed "today" is injected so "last month" and similar are deterministic.
 */
class QueryParserTest {

    private val today = LocalDate.of(2026, 9, 30)
    private val parser = QueryParser(
        dateParser = com.ssintelligence.app.search.parser.DateQueryParser(today = { today }),
    )

    private fun parse(query: String) = parser.parse(query)

    // ------------------------------------------- the four variations (§43)

    @Test
    fun `four phrasings of the same question produce the same query`() {
        val variations = listOf(
            "Find the screenshot where I saw Pixel 9a for ₹39,999",
            "Show me the Pixel 9a screenshot at ₹39,999",
            "Which screenshot had Pixel 9a priced at ₹39,999?",
            "I saw Pixel 9a for 39999 rupees, find it",
        )
        val parsed = variations.map { parse(it) }

        for (query in parsed) {
            assertEquals(listOf("pixel 9a"), query.phrases)
            assertEquals(
                listOf(PriceFilter.Exact(Currency.INR, 39999.0)),
                query.prices,
            )
        }
        assertEquals(parsed.first().describe(), parsed.last().describe())
    }

    @Test
    fun `a price with a comparison operator is not an exact match`() {
        val query = parse("Pixel phones below ₹40,000")
        assertEquals(listOf(PriceFilter.AtMost(Currency.INR, 40000.0)), query.prices)
        assertTrue(query.phrases.contains("pixel phones"))
    }

    @Test
    fun `a combined query keeps both the text and the price`() {
        val query = parse("Find the screenshot where I saw AirPods for ₹12,999")
        assertEquals(listOf("airpods"), query.textTerms)
        assertEquals(listOf(PriceFilter.Exact(Currency.INR, 12999.0)), query.prices)
    }

    // --------------------------------------------------------- intent (§6)

    @Test
    fun `a retrieval request is FIND`() {
        assertEquals(SearchIntent.FIND, parse("Find my Pixel 9 screenshot").intent)
        assertEquals(SearchIntent.FIND, parse("Which screenshot had Pixel 9?").intent)
    }

    @Test
    fun `a structural request is FILTER`() {
        assertEquals(SearchIntent.FILTER, parse("show duplicate screenshots").intent)
        assertEquals(SearchIntent.FILTER, parse("screenshots from amazon.in").intent)
        assertEquals(SearchIntent.FILTER, parse("screenshots with prices").intent)
    }

    @Test
    fun `a bare noun is a plain SEARCH`() {
        assertEquals(SearchIntent.SEARCH, parse("Pixel 9").intent)
    }

    @Test
    fun `an empty query is a BROWSE`() {
        val query = parse("   ")
        assertEquals(SearchIntent.BROWSE, query.intent)
        assertTrue(query.isEmpty)
    }

    // ------------------------------------------------------ dates (§11, §12)

    @Test
    fun `from last month produces a month range and no text terms`() {
        val query = parse("Find the Amazon screenshot from last month")
        assertEquals("last month", query.dateFilters.single().label)
        assertEquals(listOf("amazon"), query.textTerms)
        assertTrue(query.timeRange != null)
    }

    @Test
    fun `from September 2026 produces the documented range`() {
        val range = parse("screenshots from September 2026").timeRange
        assertTrue(range != null)
        val zone = ZoneId.systemDefault()
        val start = range!!.startMillis
        val endExclusive = range.endMillis
        // Boundaries are local midnight, so the range covers exactly September.
        assertEquals(
            LocalDate.of(2026, 9, 1),
            Instant.ofEpochMilli(start).atZone(zone).toLocalDate(),
        )
        assertEquals(
            LocalDate.of(2026, 10, 1),
            Instant.ofEpochMilli(endExclusive).atZone(zone).toLocalDate(),
        )
    }

    // ------------------------------------------------------ URLs (§13)

    @Test
    fun `a domain query normalizes to a host`() {
        assertEquals(listOf("amazon.in"), parse("screenshots from https://www.amazon.in/deals").urls)
    }

    @Test
    fun `an ordinary domain is handled like any other`() {
        assertEquals(listOf("example.com"), parse("find the screenshot containing example.com").urls)
    }

    // ------------------------------------------- phones and codes (§14, §15)

    @Test
    fun `a phone query normalizes to the indexed form`() {
        assertEquals(
            listOf("+919876543210"),
            parse("find screenshot containing 9876543210").phoneNumbers,
        )
    }

    @Test
    fun `a code query matches but the value is not treated as a term`() {
        val query = parse("find the screenshot with OTP 483921")
        assertEquals(listOf("483921"), query.otpCodes)
        assertTrue(query.textTerms.isEmpty())
        // describe() must never echo the code.
        assertFalse(query.describe().contains("483921"))
        assertTrue(query.describe().contains("one-time code"))
    }

    // ----------------------------------------------------- duplicates (§40)

    @Test
    fun `show duplicate screenshots asks for duplicates, not the word`() {
        val query = parse("show duplicate screenshots")
        assertEquals(setOf(ContentType.DUPLICATES), query.contentTypes)
        assertTrue(query.textTerms.isEmpty())
        assertEquals(SearchIntent.FILTER, query.intent)
    }

    // --------------------------------------------------- combined examples

    @Test
    fun `every worked example in the specification parses sensibly`() {
        val cases = listOf(
            "Find the screenshot where I saw AirPods for ₹12,999",
            "Find the Amazon screenshot from last month",
            "Show screenshots containing a flight ticket",
            "Find the screenshot with 9876543210",
            "Show screenshots with prices below ₹5,000",
            "Find screenshots from September",
            "Show duplicate screenshots",
            "Find the screenshot containing github.com",
        )
        for (case in cases) {
            val query = parse(case)
            assertTrue("\"$case\" produced nothing at all", !query.isEmpty)
        }
    }

    @Test
    fun `flight ticket is preserved as a phrase`() {
        assertEquals(listOf("flight ticket"), parse("Show screenshots containing a flight ticket").phrases)
    }

    // --------------------------------------------------------- ordering

    @Test
    fun `parse is deterministic`() {
        val query = "Find the screenshot where I saw Pixel 9a for ₹39,999"
        assertEquals(parse(query), parse(query))
    }

    @Test
    fun `the original query is preserved for display`() {
        val raw = "  Find Pixel 9a  "
        assertEquals(raw, parse(raw).originalQuery)
    }

    // ------------------------------------------------------ describe()

    @Test
    fun `describe uses plain words and no parser vocabulary`() {
        val described = parse("Find the screenshot where I saw Pixel 9a for ₹39,999").describe()
        assertEquals("pixel 9a · ₹39,999", described)
        assertFalse(described.contains("intent="))
        assertFalse(described.contains("Exact"))
    }

    @Test
    fun `describe falls back to everything when nothing was understood`() {
        assertEquals("everything", SearchQuery(originalQuery = "").describe())
    }

    // ------------------------------------------------------ fts terms

    @Test
    fun `fts terms include the words inside phrases`() {
        val query = parse("Pixel 9a")
        assertEquals(listOf("pixel", "9a"), query.ftsTerms)
    }
}
