package com.ssintelligence.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snippet extraction and highlighting (§26).
 */
class SnippetBuilderTest {

    @Test
    fun `the window opens at the first match`() {
        val ocr = "Store Home. Offers. Pixel 9a 5G 128GB. ₹39,999. Add to cart."
        val snippet = SnippetBuilder.build(ocr, listOf("pixel 9a"))!!
        assertTrue(snippet.text.contains("Pixel 9a"))
    }

    @Test
    fun `whitespace is collapsed`() {
        val snippet = SnippetBuilder.build("Pixel\n9a\n\n5G", listOf("pixel 9a"))!!
        assertTrue(!snippet.text.contains("\n"))
        assertTrue(snippet.text.contains("Pixel 9a 5G"))
    }

    @Test
    fun `highlights point at the matched characters`() {
        val snippet = SnippetBuilder.build("Amazon Pixel 9a 5G", listOf("pixel"))!!
        val range = snippet.highlights.single()
        // The snippet keeps the screenshot's own capitalisation.
        assertEquals("Pixel", snippet.text.substring(range.first, range.last + 1))
    }

    @Test
    fun `a leading match gets an ellipsis only when text precedes it`() {
        val withPrefix = SnippetBuilder.build("a".repeat(400) + " Pixel", listOf("pixel"))!!
        assertTrue(withPrefix.text.startsWith("…"))
        val atStart = SnippetBuilder.build("Pixel 9a", listOf("pixel"))!!
        assertTrue(!atStart.text.startsWith("…"))
    }

    @Test
    fun `trailing text is elided with an ellipsis`() {
        val snippet = SnippetBuilder.build("Pixel " + "b".repeat(400), listOf("pixel"))!!
        assertTrue(snippet.text.endsWith("…"))
    }

    @Test
    fun `a query with no matching term shows the head of the text`() {
        val snippet = SnippetBuilder.build("Flight ticket booking reference", listOf("pixel"))!!
        assertTrue(snippet.text.startsWith("Flight ticket"))
        assertTrue(snippet.highlights.isEmpty())
    }

    @Test
    fun `empty text produces no snippet at all`() {
        assertNull(SnippetBuilder.build("", listOf("pixel")))
        assertNull(SnippetBuilder.build("   \n ", listOf("pixel")))
    }

    @Test
    fun `the window never exceeds the requested length apart from ellipses`() {
        val ocr = (1..200).joinToString(" ") { "word$it" } + " Pixel 9a"
        val snippet = SnippetBuilder.build(ocr, listOf("pixel"), maxLength = 60)!!
        assertTrue(snippet.text.length <= 64)
    }

    @Test
    fun `the window is not cut in the middle of a word`() {
        val ocr = (1..100).joinToString(" ") { "word$it" } + " Pixel 9a"
        val snippet = SnippetBuilder.build(ocr, listOf("pixel"), maxLength = 40)!!
        val body = snippet.text.removePrefix("…").removeSuffix("…")
        assertTrue("window started mid-word: $body", body.first().isWhitespace())
    }

    @Test
    fun `a repeated term is highlighted more than once`() {
        val snippet = SnippetBuilder.build("Pixel 9a and Pixel 9a Pro", listOf("pixel"))!!
        assertTrue(snippet.highlights.size >= 2)
    }

    @Test
    fun `highlight ranges stay inside the snippet text`() {
        val snippet = SnippetBuilder.build("Pixel 9a 5G " + "x".repeat(300), listOf("pixel", "9a"))!!
        snippet.highlights.forEach { range ->
            assertTrue(range.first >= 0)
            assertTrue(range.last < snippet.text.length)
            assertTrue(range.first <= range.last)
        }
    }
}
