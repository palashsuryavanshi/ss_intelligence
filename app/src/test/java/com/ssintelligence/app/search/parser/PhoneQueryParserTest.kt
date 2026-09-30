package com.ssintelligence.app.search.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-number understanding (§14) and code handling (§15).
 *
 * Normalization must match Phase 1 exactly, because the index stores one
 * canonical form and the query has to produce the same one.
 */
class PhoneQueryParserTest {

    private val parser = PhoneQueryParser()

    private fun single(query: String): String {
        val parsed = parser.parse(query)
        assertEquals("expected one number in \"$query\"", 1, parsed.size)
        return parsed.single().value
    }

    @Test
    fun `every Indian formatting collapses to one canonical form`() {
        val expected = "+919876543210"
        assertEquals(expected, single("find screenshot containing 9876543210"))
        assertEquals(expected, single("screenshots with +91 98765 43210"))
        assertEquals(expected, single("screenshots with +91-98765-43210"))
        assertEquals(expected, single("screenshots with 09876543210"))
        assertEquals(expected, single("screenshots with 91 98765 43210"))
    }

    @Test
    fun `numbers outside the Indian series are not phones`() {
        assertTrue(parser.parse("screenshot 1234567890").isEmpty())
        assertTrue(parser.parse("screenshot 5678901234").isEmpty())
    }

    @Test
    fun `a longer digit run is not split into a phone`() {
        assertTrue(parser.parse("reference 98765432109876").isEmpty())
    }

    @Test
    fun `a price is not a phone number`() {
        assertTrue(parser.parse("₹9876543210").isEmpty())
        assertTrue(parser.parse("Rs 9876543210").isEmpty())
    }

    @Test
    fun `the identifier guard is off for a deliberate query`() {
        // Someone typing "order 9876543210" means that number.
        assertEquals("+919876543210", single("order 9876543210"))
    }

    @Test
    fun `the claimed span is just the number`() {
        val query = "find the screenshot containing 9876543210"
        val parsed = parser.parse(query).single()
        assertEquals("9876543210", query.substring(parsed.span.start, parsed.span.end))
    }

    @Test
    fun `an already-claimed region is skipped`() {
        val claimed = listOf(QuerySpan(0, 12))
        assertTrue(parser.parse("9876543210", claimed).isEmpty())
    }
}
