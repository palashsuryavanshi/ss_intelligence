package com.ssintelligence.app.search.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-time-code understanding (§15) and its false-positive guards (§44).
 */
class OtpQueryParserTest {

    private val parser = OtpQueryParser()

    private fun single(query: String): String {
        val parsed = parser.parse(query)
        assertEquals("expected one code in \"$query\"", 1, parsed.size)
        return parsed.single().value
    }

    @Test
    fun `a labelled code is found`() {
        assertEquals("483921", single("find the screenshot with OTP 483921"))
        assertEquals("483921", single("screenshot with otp: 483921"))
        assertEquals("483921", single("one time code 483921"))
        assertEquals("483921", single("verification code 483921"))
        assertEquals("483921", single("2fa code 483921"))
    }

    @Test
    fun `a bare number is never a code`() {
        // The §44 guard: no context word, no code.
        assertTrue(parser.parse("987654").isEmpty())
        assertTrue(parser.parse("find screenshot 987654").isEmpty())
        assertTrue(parser.parse("483921").isEmpty())
    }

    @Test
    fun `a phone number is never a code`() {
        assertTrue(parser.parse("screenshot with code sent to 9876543210").isEmpty())
        assertTrue(parser.parse("otp for 9876543210").isEmpty())
    }

    @Test
    fun `a price is never a code`() {
        assertTrue(parser.parse("otp ₹39999").isEmpty())
    }

    @Test
    fun `a code needs at least four digits`() {
        assertTrue(parser.parse("otp 123").isEmpty())
        assertEquals("1234", single("otp 1234"))
    }

    @Test
    fun `more than eight digits is not a code`() {
        assertTrue(parser.parse("otp 123456789").isEmpty())
    }

    @Test
    fun `mentionsCodes recognises the intent without a value`() {
        assertTrue(parser.mentionsCodes("show me my OTPs"))
        assertTrue(parser.mentionsCodes("screenshots with one-time codes"))
        assertFalse(parser.mentionsCodes("screenshots with prices"))
    }

    @Test
    fun `the claimed span covers the label and the value`() {
        val query = "find the screenshot with OTP 483921 please"
        val parsed = parser.parse(query).single()
        assertEquals("OTP 483921", query.substring(parsed.span.start, parsed.span.end))
    }
}
