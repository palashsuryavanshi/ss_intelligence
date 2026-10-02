package com.ssintelligence.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The anti-hallucination guard. */
class ResponseValidatorTest {

    private val evidence = listOf(
        Evidence(
            screenshotId = 1, dateAdded = 1_700_000_000L, filename = "a.png",
            prices = listOf("INR" to 39999.0), hosts = listOf("amazon.in"),
            ocrExcerpt = "Pixel 9a", entities = emptySet(),
            sensitivity = SensitivityLevel.NORMAL, signals = listOf("text match"),
        ),
        Evidence(
            screenshotId = 2, dateAdded = 1_700_0086_400L, filename = "b.png",
            prices = listOf("INR" to 41999.0), hosts = listOf("flipkart.com"),
            ocrExcerpt = "Pixel 9a", entities = emptySet(),
            sensitivity = SensitivityLevel.NORMAL, signals = listOf("text match"),
        ),
    )

    @Test
    fun `a grounded answer passes unchanged`() {
        val result = ResponseValidator.validate(
            "I found 2 screenshots. The lowest is ₹39,999.",
            evidence,
        )
        assertTrue(result.passed)
        assertEquals("I found 2 screenshots. The lowest is ₹39,999.", result.validated)
    }

    @Test
    fun `an unsupported price is stripped`() {
        val result = ResponseValidator.validate(
            "I found 2 screenshots. The lowest is ₹39,999. It also showed ₹99,999.",
            evidence,
        )
        assertFalse(result.passed)
        assertTrue(!result.validated.contains("99,999"))
        assertTrue(result.validated.contains("39,999"))
    }

    @Test
    fun `an unsupported year is stripped`() {
        val result = ResponseValidator.validate(
            "Found in 2021 and 2026.",
            evidence,
        )
        assertFalse(result.passed)
        assertTrue(!result.validated.contains("2021"))
    }

    @Test
    fun `an unsupported website is stripped`() {
        val result = ResponseValidator.validate(
            "Found on amazon.in and on evil.example.com.",
            evidence,
        )
        assertFalse(result.passed)
        assertTrue(!result.validated.contains("evil.example.com"))
    }

    @Test
    fun `a count larger than the evidence is stripped`() {
        val result = ResponseValidator.validate(
            "I found 5 screenshots about that.",
            evidence,
        )
        assertFalse(result.passed)
        assertTrue(!result.validated.contains("5 screenshots"))
    }

    @Test
    fun `a sensitive answer is masked`() {
        val sensitive = evidence + Evidence(
            screenshotId = 3, dateAdded = 1_700_000_000L, filename = "c.png",
            prices = emptyList(), hosts = emptyList(), ocrExcerpt = "OTP 483921",
            entities = emptySet(), sensitivity = SensitivityLevel.HIGHLY_SENSITIVE,
            signals = listOf("text match"),
        )
        val result = ResponseValidator.validate(
            "I found 3 screenshots. The OTP is 483921.",
            sensitive,
        )
        assertFalse(result.passed)
        assertTrue(!result.validated.contains("483921"))
    }

    @Test
    fun `an empty answer gets an honest fallback`() {
        val result = ResponseValidator.validate("", emptyList())
        assertTrue(result.validated.contains("could not"))
    }
}
