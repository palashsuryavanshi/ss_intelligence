package com.ssintelligence.app.search.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Palette and shape understanding (§9, §25 Phase 4). */
class VisualQueryParserTest {

    private val parser = VisualQueryParser()

    @Test
    fun `a color word becomes a palette filter`() {
        val result = parser.parse("find the blue phone screenshot")
        assertEquals(listOf("blue"), result.colors)
        assertFalse(result.longOnly)
    }

    @Test
    fun `dark and light are brightness asks`() {
        assertEquals(listOf("dark"), parser.parse("dark screenshots").colors)
        assertEquals(listOf("light"), parser.parse("light screenshots").colors)
    }

    @Test
    fun `dark mode stays a phrase`() {
        // "dark mode" is UI language, not a palette ask: the word must survive
        // for the keyword extractor.
        val result = parser.parse("dark mode screenshots")
        assertTrue(result.colors.isEmpty())
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `color synonyms resolve`() {
        assertEquals(listOf("gray"), parser.parse("grey screenshots").colors)
        assertEquals(listOf("purple"), parser.parse("violet screenshots").colors)
    }

    @Test
    fun `unknown color words are ignored`() {
        assertTrue(parser.parse("turquoise screenshots").colors.isEmpty())
    }

    @Test
    fun `long screenshots are a shape ask`() {
        val result = parser.parse("show me long screenshots")
        assertTrue(result.longOnly)
        assertTrue(result.colors.isEmpty())
    }

    @Test
    fun `tall and stitched count as long`() {
        assertTrue(parser.parse("tall screenshots").longOnly)
        assertTrue(parser.parse("stitched screenshots").longOnly)
        assertTrue(parser.parse("full page screenshots").longOnly)
    }

    @Test
    fun `a plain query claims nothing`() {
        val result = parser.parse("find the screenshot of Pixel 9a")
        assertTrue(result.colors.isEmpty())
        assertFalse(result.longOnly)
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `an already-claimed region is skipped`() {
        val claimed = listOf(QuerySpan(9, 13))
        val result = parser.parse("find the blue phone", claimed)
        assertTrue(result.colors.isEmpty())
    }

    @Test
    fun `the claimed span is just the color word`() {
        val query = "find the blue phone"
        val result = parser.parse(query)
        val span = result.spans.single()
        assertEquals("blue", query.substring(span.start, span.end))
    }
}
