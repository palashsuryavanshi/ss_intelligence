package com.ssintelligence.app.security

import com.ssintelligence.app.ui.common.OcrMarkdown
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMarkdownTest {

    private val body = Color.White
    private val accent = Color.Blue
    private val muted = Color.Gray
    private val codeBg = Color.DarkGray

    private fun render(text: String) =
        OcrMarkdown.toAnnotatedString(text, body, accent, muted, codeBg).text

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("Pixel 9a for Rs 39999", render("Pixel 9a for Rs 39999"))
    }

    @Test
    fun `header marker is stripped`() {
        assertEquals("Booking confirmed", render("## Booking confirmed"))
    }

    @Test
    fun `bold markers are stripped`() {
        assertEquals("Total due", render("**Total due**"))
    }

    @Test
    fun `italic markers are stripped`() {
        assertEquals("note", render("*note*"))
    }

    @Test
    fun `inline code markers are stripped`() {
        assertEquals("OTP 123456", render("OTP `123456`"))
    }

    @Test
    fun `bullet becomes bullet char`() {
        assertEquals("• Milk", render("- Milk"))
    }

    @Test
    fun `ordered list keeps number`() {
        assertEquals("1. First", render("1. First"))
    }

    @Test
    fun `link shows link text`() {
        assertEquals("store", render("[store](https://example.com)"))
    }

    @Test
    fun `fenced code block keeps content without fences`() {
        val out = render("```\nsecret 123\n```")
        assertTrue(out.contains("secret 123"))
        assertTrue(!out.contains("```"))
    }

    @Test
    fun `multiline text preserves line breaks`() {
        assertEquals("line one\nline two", render("line one\nline two"))
    }
}