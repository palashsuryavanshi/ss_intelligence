package com.ssintelligence.app.ml.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL extraction (§13, §36).
 */
class UrlExtractorTest {

    private val extractor = UrlExtractor()

    @Test
    fun `extracts a plain https url from a sentence`() {
        val result = extractor.extract("Visit https://example.com today")
        assertEquals(listOf("https://example.com"), result.map { it.url })
    }

    @Test
    fun `normalizes a bare domain to https`() {
        val result = extractor.extract("example.com/page")
        assertEquals(listOf("https://example.com/page"), result.map { it.url })
    }

    @Test
    fun `normalizes www prefix to https and keeps host`() {
        val result = extractor.extract("Check www.example.com now")
        assertEquals(listOf("https://www.example.com"), result.map { it.url })
        assertEquals("www.example.com", result.first().host)
    }

    @Test
    fun `keeps http scheme when present`() {
        val result = extractor.extract("http://example.com/a?b=c")
        assertEquals(listOf("http://example.com/a?b=c"), result.map { it.url })
    }

    @Test
    fun `drops trailing sentence punctuation`() {
        val result = extractor.extract("Open amazon.in, then flipkart.com.")
        assertEquals(listOf("https://amazon.in", "https://flipkart.com"), result.map { it.url })
    }

    @Test
    fun `does not treat ordinary words as urls`() {
        val result = extractor.extract("The quick brown fox jumps over the lazy dog")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `does not treat an email address as a url`() {
        val result = extractor.extract("Write to support@example.com for help")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `does not treat a version number as a url`() {
        val result = extractor.extract("Version 2.1 released")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `excludes screen recording folders from the url tld list`() {
        val result = extractor.extract("see notes.doc")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts multiple urls and dedupes`() {
        val result = extractor.extract("https://a.com and https://b.com and https://a.com")
        assertEquals(listOf("https://a.com", "https://b.com"), result.map { it.url })
    }

    @Test
    fun `lowercases host but preserves path case`() {
        val result = extractor.extract("HTTPS://Example.COM/Path/To/Page")
        assertEquals("https://example.com/Path/To/Page", result.first().url)
    }

    @Test
    fun `returns nothing for blank input`() {
        assertTrue(extractor.extract("").isEmpty())
        assertTrue(extractor.extract("   ").isEmpty())
    }
}
