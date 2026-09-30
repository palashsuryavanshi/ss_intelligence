package com.ssintelligence.app.ml.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone number extraction (§14, §36).
 */
class PhoneExtractorTest {

    private val extractor = PhoneExtractor()

    @Test
    fun `normalizes a formatted indian number`() {
        val result = extractor.extract("Call +91 98765 43210")
        assertEquals(1, result.size)
        assertEquals("+919876543210", result.first().normalized)
    }

    @Test
    fun `normalizes a hyphenated indian number`() {
        val result = extractor.extract("Call +91-98765-43210")
        assertEquals("+919876543210", result.first().normalized)
    }

    @Test
    fun `normalizes a bare ten digit number`() {
        val result = extractor.extract("Call 9876543210")
        assertEquals("+919876543210", result.first().normalized)
    }

    @Test
    fun `normalizes a number with trunk zero`() {
        val result = extractor.extract("Call 09876543210")
        assertEquals("+919876543210", result.first().normalized)
    }

    @Test
    fun `normalizes a number with country code but no plus`() {
        val result = extractor.extract("Call 91 98765 43210")
        assertEquals("+919876543210", result.first().normalized)
    }

    @Test
    fun `does not match an order id`() {
        assertTrue(extractor.extract("Order ID: 9876543210").isEmpty())
    }

    @Test
    fun `does not match a number embedded in a longer digit run`() {
        assertTrue(extractor.extract("1987654321098").isEmpty())
    }

    @Test
    fun `does not match a number starting with an invalid series digit`() {
        // Indian mobile series begin with 6-9.
        assertTrue(extractor.extract("Call 1234567890").isEmpty())
    }

    @Test
    fun `dedupes the same number written twice`() {
        val result = extractor.extract("9876543210 and +91 98765 43210")
        assertEquals(1, result.size)
    }

    @Test
    fun `records the country code`() {
        assertEquals("IN", extractor.extract("9876543210").first().country)
    }

    @Test
    fun `returns nothing for blank input`() {
        assertTrue(extractor.extract("").isEmpty())
    }
}
