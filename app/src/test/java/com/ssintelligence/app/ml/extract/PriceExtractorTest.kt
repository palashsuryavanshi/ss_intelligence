package com.ssintelligence.app.ml.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Price and currency extraction (§15, §36).
 *
 * The central guarantee: a bare number is never a price. Only an explicit
 * currency marker turns a number into a price.
 */
class PriceExtractorTest {

    private val extractor = PriceExtractor()

    @Test
    fun `extracts rupee price with indian grouping`() {
        val result = extractor.extract("Pixel 9a - ₹39,999")
        assertEquals(1, result.size)
        assertEquals("INR", result.first().currency)
        assertEquals(39999.0, result.first().amount, 0.001)
    }

    @Test
    fun `extracts rupee price with a space after the symbol`() {
        val result = extractor.extract("Price: ₹ 39,999 only")
        assertEquals("INR", result.first().currency)
        assertEquals(39999.0, result.first().amount, 0.001)
    }

    @Test
    fun `extracts Rs prefix`() {
        val result = extractor.extract("Total Rs. 39,999")
        assertEquals("INR", result.first().currency)
        assertEquals(39999.0, result.first().amount, 0.001)
    }

    @Test
    fun `extracts Rs without a dot`() {
        val result = extractor.extract("Total Rs 1200")
        assertEquals("INR", result.first().currency)
        assertEquals(1200.0, result.first().amount, 0.001)
    }

    @Test
    fun `extracts INR code prefix`() {
        val result = extractor.extract("INR 39999")
        assertEquals("INR", result.first().currency)
        assertEquals(39999.0, result.first().amount, 0.001)
    }

    @Test
    fun `extracts dollar price`() {
        val result = extractor.extract("Only \$299")
        assertEquals("USD", result.first().currency)
        assertEquals(299.0, result.first().amount, 0.001)
    }

    @Test
    fun `extracts USD code`() {
        val result = extractor.extract("USD 299")
        assertEquals("USD", result.first().currency)
        assertEquals(299.0, result.first().amount, 0.001)
    }

    @Test
    fun `extracts euro and pound prices`() {
        assertEquals("EUR", extractor.extract("€24").first().currency)
        assertEquals("GBP", extractor.extract("£15").first().currency)
    }

    @Test
    fun `an otp is not a price`() {
        assertTrue(extractor.extract("OTP: 482913").isEmpty())
    }

    @Test
    fun `an order id is not a price`() {
        assertTrue(extractor.extract("Order ID: 182739").isEmpty())
    }

    @Test
    fun `a plain number is not a price`() {
        assertTrue(extractor.extract("You have 5 items left").isEmpty())
    }

    @Test
    fun `a date is not a price`() {
        assertTrue(extractor.extract("Dated 30/09/2026").isEmpty())
    }

    @Test
    fun `rejects malformed grouping`() {
        assertTrue(extractor.extract("₹39,,999").isEmpty())
    }

    @Test
    fun `rejects zero and negative-value prices`() {
        assertTrue(extractor.extract("₹0").isEmpty())
    }

    @Test
    fun `handles decimals`() {
        val result = extractor.extract("Total \$19.99")
        assertEquals(19.99, result.first().amount, 0.001)
    }

    @Test
    fun `extracts multiple prices`() {
        val result = extractor.extract("Was \$299 now \$249")
        assertEquals(2, result.size)
    }
}
