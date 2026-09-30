package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.Currency
import com.ssintelligence.app.search.PriceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Price understanding (§9, §10) and the false-positive guards (§44).
 */
class PriceQueryParserTest {

    private val parser = PriceQueryParser()

    private fun single(query: String): PriceFilter {
        val parsed = parser.parse(query)
        assertEquals("expected exactly one price in \"$query\"", 1, parsed.size)
        return parsed.single().value
    }

    private fun none(query: String) {
        assertTrue(
            "expected no price in \"$query\" but got ${parser.parse(query).map { it.value }}",
            parser.parse(query).isEmpty(),
        )
    }

    // ------------------------------------------------------- currency forms

    @Test
    fun `rupee symbol resolves to INR`() {
        val filter = single("Pixel 9a for ₹39,999")
        assertEquals(PriceFilter.Exact(Currency.INR, 39999.0), filter)
    }

    @Test
    fun `Rs, INR and rupees all normalize to INR`() {
        assertEquals(Currency.INR, (single("phone at Rs 40000") as PriceFilter.Exact).currency)
        assertEquals(Currency.INR, (single("phone at INR 40000") as PriceFilter.Exact).currency)
        assertEquals(
            PriceFilter.Exact(Currency.INR, 39999.0),
            single("I saw Pixel 9a for 39999 rupees, find it"),
        )
    }

    @Test
    fun `dollars and euros stay their own currencies`() {
        assertEquals(Currency.USD, (single("laptop for $1,299") as PriceFilter.Exact).currency)
        assertEquals(Currency.EUR, (single("laptop for €1,299") as PriceFilter.Exact).currency)
        assertEquals(Currency.GBP, (single("laptop for £1,299") as PriceFilter.Exact).currency)
    }

    @Test
    fun `currencies are never converted`() {
        // $400 must not become ₹400, and must not satisfy an INR filter.
        val dollars = single("under $400") as PriceFilter.AtMost
        assertEquals(Currency.USD, dollars.currency)
        assertTrue(!dollars.contains(400.0, Currency.INR))
        assertTrue(dollars.contains(400.0, Currency.USD))
    }

    @Test
    fun `indian and western digit grouping both parse`() {
        assertEquals(39999.0, (single("₹39,999") as PriceFilter.Exact).amount, 0.01)
        assertEquals(100000.0, (single("₹1,00,000") as PriceFilter.Exact).amount, 0.01)
        assertEquals(40000.0, (single("below 40 000") as PriceFilter.AtMost).amount, 0.01)
    }

    // ------------------------------------------------------------ operators

    @Test
    fun `below and under become an upper bound`() {
        for (word in listOf("below", "under", "less than", "cheaper than", "up to", "at most")) {
            val filter = single("Pixel phones $word ₹40,000")
            assertEquals("$word should be an upper bound", PriceFilter.AtMost(Currency.INR, 40000.0), filter)
        }
    }

    @Test
    fun `above and over become a lower bound`() {
        for (word in listOf("above", "over", "greater than", "more than", "at least")) {
            val filter = single("laptops $word ₹40,000")
            assertEquals("$word should be a lower bound", PriceFilter.AtLeast(Currency.INR, 40000.0), filter)
        }
    }

    @Test
    fun `around produces the documented five percent band`() {
        val filter = single("phone around ₹40,000") as PriceFilter.Range
        assertEquals(38000.0, filter.min, 0.01)
        assertEquals(42000.0, filter.max, 0.01)
        assertTrue(filter.contains(40000.0, Currency.INR))
        assertTrue(filter.contains(38500.0, Currency.INR))
        assertTrue(!filter.contains(30000.0, Currency.INR))
    }

    @Test
    fun `tolerance is the same five percent for every approximate word`() {
        for (word in listOf("around", "approximately", "about", "roughly", "near", "close to")) {
            val filter = single("phone $word ₹40,000") as PriceFilter.Range
            assertEquals("$word band", 38000.0, filter.min, 0.01)
            assertEquals("$word band", 42000.0, filter.max, 0.01)
        }
    }

    @Test
    fun `between produces an explicit range`() {
        val filter = single("phones between ₹10,000 and ₹20,000") as PriceFilter.Range
        assertEquals(10000.0, filter.min, 0.01)
        assertEquals(20000.0, filter.max, 0.01)
    }

    @Test
    fun `a bare currency-marked amount is an exact price`() {
        assertEquals(PriceFilter.Exact(Currency.INR, 39999.0), single("₹39,999"))
        assertEquals(PriceFilter.Exact(Currency.USD, 250.0), single("$250"))
    }

    @Test
    fun `the word price or cost makes a bare number a price`() {
        assertEquals(PriceFilter.Exact(null, 39999.0), single("price 39999"))
        assertEquals(PriceFilter.Exact(null, 39999.0), single("it cost 39999"))
    }

    // ------------------------------------------------------- false positives

    @Test
    fun `an order id is not a price`() {
        none("Order ID: 39999")
        none("order 39999")
        none("invoice no 39999")
        none("upi ref 39999")
        none("booking id 39999")
    }

    @Test
    fun `a phone number is not a price`() {
        none("find the screenshot with 9876543210")
        none("call 9876543210")
    }

    @Test
    fun `a one-time code is not a price`() {
        none("otp 483921")
        none("verification code 483921")
    }

    @Test
    fun `a count is not a price`() {
        none("5 items in the cart")
        none("2 TB of storage")
    }

    @Test
    fun `a zero or absurd amount is rejected`() {
        none("price 0")
        none("price 999999999999999")
    }

    @Test
    fun `underground is not the operator under`() {
        none("underground parking 40000")
    }

    // ----------------------------------------------------------- span claims

    @Test
    fun `a currency word between the operator and the amount is stepped over`() {
        // Found on device: "under Rs 40000" lost its operator because the
        // currency word sat between them.
        assertEquals(
            PriceFilter.AtMost(Currency.INR, 40000.0),
            single("under Rs 40000"),
        )
        assertEquals(
            PriceFilter.AtLeast(Currency.INR, 40000.0),
            single("above Rs 40000"),
        )
        val approximate = single("around Rs 40000") as PriceFilter.Range
        assertEquals(38000.0, approximate.min, 0.01)
        assertEquals(42000.0, approximate.max, 0.01)
    }

    @Test
    fun `the claimed span covers the operator and the currency word`() {
        val query = "under Rs 40000"
        val parsed = parser.parse(query).single()
        assertEquals("under Rs 40000", query.substring(parsed.span.start, parsed.span.end))
    }

    @Test
    fun `the claimed span covers the operator and trigger words`() {
        val query = "Which screenshot had Pixel 9a priced at ₹39,999?"
        val parsed = parser.parse(query).single()
        assertEquals("priced at ₹39,999", query.substring(parsed.span.start, parsed.span.end))
    }

    @Test
    fun `the claimed span covers a trailing currency word`() {
        val query = "Pixel 9a for 39999 rupees"
        val parsed = parser.parse(query).single()
        // "for" is a price trigger, so it belongs to the expression too.
        assertEquals("for 39999 rupees", query.substring(parsed.span.start, parsed.span.end))
    }

    @Test
    fun `the claimed span covers the operator and a leading currency word`() {
        val query = "below Rs 40000"
        val parsed = parser.parse(query).single()
        // "below" is claimed too, so it cannot leak into the search terms.
        assertEquals("below Rs 40000", query.substring(parsed.span.start, parsed.span.end))
    }

    @Test
    fun `the claimed span does not swallow a preceding search term`() {
        val query = "Pixel 9a ₹39,999"
        val parsed = parser.parse(query).single()
        assertEquals("₹39,999", query.substring(parsed.span.start, parsed.span.end))
    }

    @Test
    fun `a price inside a longer query is still found`() {
        val parsed = parser.parse("Find the screenshot where I saw Pixel 9a for ₹39,999")
        assertEquals(1, parsed.size)
        assertEquals(PriceFilter.Exact(Currency.INR, 39999.0), parsed.single().value)
    }

    @Test
    fun `already-claimed regions are skipped`() {
        val claimed = listOf(QuerySpan(0, 6))
        assertTrue(parser.parse("9876543210", claimed).isEmpty())
    }

    // ----------------------------------------------------------- formatting

    @Test
    fun `display reads back as something a person would type`() {
        assertEquals("₹39,999", (single("for ₹39,999") as PriceFilter.Exact).display())
        assertEquals(
            "up to ₹40,000",
            (single("below ₹40,000") as PriceFilter.AtMost).display(),
        )
        assertEquals(
            "₹38,000 – ₹42,000",
            (single("around ₹40,000") as PriceFilter.Range).display(),
        )
    }

    @Test
    fun `a null currency band matches any currency`() {
        val filter = single("price 40000") as PriceFilter.Exact
        assertNull(filter.currency)
        assertTrue(filter.contains(40000.0, Currency.INR))
        assertTrue(filter.contains(40000.0, Currency.USD))
    }
}
