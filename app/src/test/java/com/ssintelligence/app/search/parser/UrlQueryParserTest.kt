package com.ssintelligence.app.search.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Domain and URL understanding (§13) and its false-positive guards (§44).
 */
class UrlQueryParserTest {

    private val parser = UrlQueryParser()

    private fun single(query: String): String {
        val parsed = parser.parse(query)
        assertEquals("expected one host in \"$query\"", 1, parsed.size)
        return parsed.single().value
    }

    @Test
    fun `a bare domain is understood`() {
        assertEquals("amazon.in", single("screenshots from amazon.in"))
        assertEquals("github.com", single("find the screenshot containing github.com"))
    }

    @Test
    fun `www is not part of the host`() {
        assertEquals("amazon.in", single("screenshots from www.amazon.in"))
    }

    @Test
    fun `a scheme is stripped`() {
        assertEquals("amazon.in", single("screenshots from https://amazon.in"))
        assertEquals("amazon.in", single("screenshots from http://www.amazon.in"))
    }

    @Test
    fun `path query and fragment are stripped but the host is kept`() {
        assertEquals("amazon.in", single("https://www.amazon.in/product/123"))
        assertEquals("amazon.in", single("https://amazon.in/s?ref=xyz"))
        assertEquals("amazon.in", single("https://amazon.in/p#reviews"))
    }

    @Test
    fun `a port is stripped`() {
        assertEquals("example.com", single("http://example.com:8080/path"))
    }

    @Test
    fun `hosts are lowercased`() {
        assertEquals("amazon.in", single("screenshots from AMAZON.IN"))
        assertEquals("amazon.in", single("HTTPS://WWW.Amazon.In/Product/1"))
    }

    @Test
    fun `subdomains are preserved so a parent-domain search can find them`() {
        // smile.amazon.in keeps its subdomain: the ranker decides whether a
        // parent-domain query matches it, not the parser.
        assertEquals("smile.amazon.in", single("screenshots from smile.amazon.in"))
    }

    @Test
    fun `two domains in one query are both found`() {
        val parsed = parser.parse("compare amazon.in and flipkart.com")
        assertEquals(listOf("amazon.in", "flipkart.com"), parsed.map { it.value })
    }

    // ------------------------------------------------------- false positives

    @Test
    fun `version numbers are not domains`() {
        assertTrue(parser.parse("app version 3.5").isEmpty())
        assertTrue(parser.parse("android 12 update").isEmpty())
        assertTrue(parser.parse("build v1.2.3").isEmpty())
    }

    @Test
    fun `a bare number is not a domain`() {
        assertTrue(parser.parse("find 39999").isEmpty())
        assertTrue(parser.parse("screenshot 9876543210").isEmpty())
    }

    @Test
    fun `a filename with an extension is treated as a host only if it looks like one`() {
        // "notes.txt" resolves to a host: three letters, no digits. Acceptable:
        // a screenshot mentioning a .txt file is worth matching on.
        assertEquals("notes.txt", single("screenshot mentioning notes.txt"))
        assertNull(parser.hostOf("readme"))
        assertNull(parser.hostOf("no-extension"))
    }

    @Test
    fun `an IP address is not treated as a domain`() {
        assertTrue(parser.parse("connected to 192.168.1.1").isEmpty())
    }

    @Test
    fun `a price is not a domain`() {
        assertTrue(parser.parse("Pixel 9a for ₹39,999").isEmpty())
        assertTrue(parser.parse("below ₹40,000").isEmpty())
    }

    @Test
    fun `the claimed span excludes the surrounding words`() {
        val query = "screenshots from amazon.in yesterday"
        val parsed = parser.parse(query).single()
        assertEquals("amazon.in", query.substring(parsed.span.start, parsed.span.end))
    }

    @Test
    fun `hostOf rejects malformed hosts`() {
        assertNull(parser.hostOf("https://"))
        assertNull(parser.hostOf("www."))
        assertNull(parser.hostOf("a..b"))
        assertNull(parser.hostOf("example.1"))
    }
}
