package com.ssintelligence.app.search

import com.ssintelligence.app.search.FtsQueryBuilder.Conjunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FTS `MATCH` expression construction, including the `OR` form that backs the
 * relaxed-search rungs (§29).
 */
class FtsQueryBuilderTest {

    @Test
    fun `terms are AND-ed with the implicit operator`() {
        assertEquals("\"pixel\"* \"9a\"*", FtsQueryBuilder.build("pixel 9a"))
    }

    @Test
    fun `single-character terms survive as prefix matches`() {
        // "Pixel 9" must keep the 9: dropping it would match every Pixel.
        assertEquals("\"pixel\"* \"9\"*", FtsQueryBuilder.build("Pixel 9"))
    }

    @Test
    fun `terms are lowercased and stripped of punctuation`() {
        assertEquals("\"pixel\"*", FtsQueryBuilder.build("Pixel."))
    }

    @Test
    fun `an explicit OR joins the terms`() {
        assertEquals(
            "\"pixel\"* OR \"9a\"*",
            FtsQueryBuilder.buildFromTerms(listOf("pixel", "9a"), Conjunction.OR),
        )
    }

    @Test
    fun `duplicates and blanks are removed`() {
        assertEquals(
            "\"pixel\"*",
            FtsQueryBuilder.buildFromTerms(listOf("Pixel", "pixel", "  ", "")),
        )
    }

    @Test
    fun `a quote inside a term is escaped for FTS4`() {
        // FTS4 embeds a literal quote by doubling it inside the string.
        assertEquals("\"say\"\"hi\"*", FtsQueryBuilder.buildFromTerms(listOf("say\"hi\"")))
    }

    @Test
    fun `no usable tokens means no expression`() {
        assertNull(FtsQueryBuilder.build("   "))
        assertNull(FtsQueryBuilder.buildFromTerms(emptyList()))
        assertNull(FtsQueryBuilder.buildFromTerms(listOf("", " ")))
    }

    @Test
    fun `the term count is bounded`() {
        val terms = (1..40).map { "term$it" }
        val expression = FtsQueryBuilder.buildFromTerms(terms)!!
        assertEquals(12, expression.split(' ').size)
    }

    @Test
    fun `like patterns escape the wildcard characters`() {
        assertEquals("100\\% off", FtsQueryBuilder.escapeLike("100% off"))
        assertEquals("a\\_b", FtsQueryBuilder.escapeLike("a_b"))
        assertEquals("back\\\\slash", FtsQueryBuilder.escapeLike("back\\slash"))
    }
}

/** Amount rendering, including the Indian digit grouping the screenshots use. */
class CurrencyTest {

    @Test
    fun `symbols and codes resolve`() {
        assertEquals(Currency.INR, Currency.resolve("₹"))
        assertEquals(Currency.INR, Currency.resolve("Rs"))
        assertEquals(Currency.INR, Currency.resolve("rupees"))
        assertEquals(Currency.USD, Currency.resolve("USD"))
        assertEquals(Currency.EUR, Currency.resolve("€"))
        assertEquals(Currency.GBP, Currency.resolve("£"))
        assertNull(Currency.resolve("franc"))
    }

    @Test
    fun `formatting restores the symbol`() {
        assertEquals("₹39,999", Currency.format(Currency.INR, 39999.0))
        assertEquals("$1,299", Currency.format(Currency.USD, 1299.0))
        assertEquals("€99", Currency.format(Currency.EUR, 99.0))
        // Grouping applies even without a currency.
        assertEquals("40,000", Currency.format(null, 40000.0))
    }

    @Test
    fun `grouping follows the Indian convention`() {
        assertEquals("39,999", Currency.groupIndianDigits("39999"))
        assertEquals("1,00,000", Currency.groupIndianDigits("100000"))
        assertEquals("12,34,567", Currency.groupIndianDigits("1234567"))
        assertEquals("999", Currency.groupIndianDigits("999"))
        assertEquals("1,000", Currency.groupIndianDigits("1000"))
    }

    @Test
    fun `an unknown currency code is shown rather than guessed`() {
        assertEquals("250 JPY", Currency.format("JPY", 250.0))
    }

    @Test
    fun `symbols are available for filter chips`() {
        assertEquals("₹", Currency.symbol(Currency.INR))
        assertNull(Currency.symbol("JPY"))
    }
}
