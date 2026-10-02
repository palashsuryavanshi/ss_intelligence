package com.ssintelligence.app.security

import com.ssintelligence.app.security.SensitiveContentDetector.SensitivityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentDetectorTest {

    @Test
    fun `OTP in OCR text is classified as authentication`() {
        val result = SensitiveContentDetector.classify("Your OTP is 829431. Do not share.")
        assertEquals(SensitivityLevel.AUTHENTICATION, result.level)
        assertTrue(result.reasons.any { it.contains("OTP") })
    }

    @Test
    fun `verification code is classified as authentication`() {
        val result = SensitiveContentDetector.classify("Verification code: 192837")
        assertEquals(SensitivityLevel.AUTHENTICATION, result.level)
    }

    @Test
    fun `UPI ID is classified as financial`() {
        val result = SensitiveContentDetector.classify("Pay to user@okaxis or user@paytm")
        assertEquals(SensitivityLevel.FINANCIAL, result.level)
    }

    @Test
    fun `Aadhaar-like number is classified as identity`() {
        val result = SensitiveContentDetector.classify("Aadhaar: 1234 5678 9012")
        assertEquals(SensitivityLevel.IDENTITY, result.level)
    }

    @Test
    fun `PAN-like identifier is classified as identity`() {
        val result = SensitiveContentDetector.classify("PAN: ABCDE1234F")
        assertEquals(SensitivityLevel.IDENTITY, result.level)
    }

    @Test
    fun `receipt flag marks as financial`() {
        val result = SensitiveContentDetector.classify("Store X receipt", hasReceipt = true)
        assertEquals(SensitivityLevel.FINANCIAL, result.level)
    }

    @Test
    fun `WhatsApp mention marks as private communication`() {
        val result = SensitiveContentDetector.classify("WhatsApp backup chat from John")
        assertEquals(SensitivityLevel.PRIVATE_COMMUNICATION, result.level)
    }

    @Test
    fun `normal text is public`() {
        val result = SensitiveContentDetector.classify("Pixel 9a review with great camera")
        assertEquals(SensitivityLevel.PUBLIC, result.level)
    }

    @Test
    fun `isHighlySensitive returns true for OTP`() {
        assertTrue(SensitiveContentDetector.isHighlySensitive("OTP: 123456"))
    }

    @Test
    fun `isHighlySensitive returns false for normal text`() {
        assertFalse(SensitiveContentDetector.isHighlySensitive("Hello world"))
    }

    @Test
    fun `price detection marks as financial`() {
        val result = SensitiveContentDetector.classify("Total: Rs. 1520.00", hasPrices = true)
        assertEquals(SensitivityLevel.FINANCIAL, result.level)
    }

    @Test
    fun `email is classified as personal`() {
        val result = SensitiveContentDetector.classify("Contact: user@example.com")
        assertEquals(SensitivityLevel.PERSONAL, result.level)
    }

    @Test
    fun `safe description is generic`() {
        val desc = SensitiveContentDetector.getSafeDescription(SensitivityLevel.AUTHENTICATION)
        assertEquals("authentication code", desc)
    }
}