package com.ssintelligence.app.ml.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTP detection (§17, §36).
 *
 * Precision matters more than recall here: a wrong OTP is worse than a missed
 * one, and screenshots are full of unrelated 4-8 digit numbers.
 */
class OtpExtractorTest {

    private val extractor = OtpExtractor()

    @Test
    fun `finds a code introduced by otp`() {
        assertEquals(listOf("483921"), extractor.extract("Your OTP is 483921").map { it.code })
    }

    @Test
    fun `finds a code after verification code`() {
        assertEquals(listOf("123456"), extractor.extract("Your verification code is 123456").map { it.code })
    }

    @Test
    fun `finds a code after verify`() {
        assertEquals(listOf("928341"), extractor.extract("Use 928341 to verify").map { it.code })
    }

    @Test
    fun `supports six four and five digit codes`() {
        assertEquals(listOf("482913"), extractor.extract("OTP: 482913").map { it.code })
        assertEquals(listOf("1234"), extractor.extract("OTP: 1234").map { it.code })
        assertEquals(listOf("12345"), extractor.extract("OTP: 12345").map { it.code })
    }

    @Test
    fun `supports eight digit codes`() {
        assertEquals(listOf("48392011"), extractor.extract("OTP: 48392011").map { it.code })
    }

    @Test
    fun `a bare number is not an otp`() {
        assertTrue(extractor.extract("You have 5 notifications").isEmpty())
    }

    @Test
    fun `a total is not an otp`() {
        assertTrue(extractor.extract("Total items 482913 in cart").isEmpty())
    }

    @Test
    fun `an order id is not an otp`() {
        assertTrue(extractor.extract("Order ID: 482913 confirmed").isEmpty())
    }

    @Test
    fun `a year is not an otp`() {
        assertTrue(extractor.extract("Enter 2026 to continue").isEmpty())
    }

    @Test
    fun `a repeated digit string is not an otp`() {
        assertTrue(extractor.extract("OTP: 111111").isEmpty())
    }

    @Test
    fun `a sequential digit string is accepted when an otp keyword is present`() {
        // A tidy digit run is plausible as a real code when the context says so.
        assertEquals(listOf("123456"), extractor.extract("OTP: 123456").map { it.code })
    }

    @Test
    fun `a compact calendar date is not an otp`() {
        assertTrue(extractor.extract("OTP: 20260930").isEmpty())
    }

    @Test
    fun `rejects unsupported code lengths`() {
        // 3 and 7 digits are not recognised code lengths.
        assertTrue(extractor.extract("OTP: 482").isEmpty())
        assertTrue(extractor.extract("OTP: 4829130").isEmpty())
    }

    @Test
    fun `a code far from its keyword is not an otp`() {
        assertTrue(
            extractor.extract(
                "Your order confirmation and delivery details are listed below " +
                    "with an unrelated total of 482913 rupees"
            ).isEmpty()
        )
    }

    @Test
    fun `finds several codes`() {
        val result = extractor.extract("OTP: 483921 and backup code 928341")
        assertEquals(2, result.size)
    }

    @Test
    fun `returns nothing for blank input`() {
        assertTrue(extractor.extract("").isEmpty())
    }
}
