package com.ssintelligence.app.search.parser

import com.ssintelligence.app.search.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filler removal and phrase preservation (§7, §8).
 */
class KeywordExtractorTest {

    private val extractor = KeywordExtractor()

    private fun extract(query: String, consumed: List<QuerySpan> = emptyList()) =
        extractor.extract(query, consumed)

    @Test
    fun `conversational filler is removed`() {
        val run = extract("Find the screenshot where I saw Pixel 9a for ₹39,999")
        assertEquals(listOf("pixel 9a"), run.phrases)
        // "find", "the", "screenshot", "where", "i", "saw", "for" are gone.
        val framing = setOf("find", "the", "screenshot", "where", "i", "saw", "for")
        assertTrue(run.terms.none { it in framing })
        assertTrue(run.terms.containsAll(listOf("pixel", "9a")))
    }

    @Test
    fun `a multi-word subject becomes a phrase`() {
        val run = extract("Pixel 9a")
        assertEquals(listOf("pixel 9a"), run.phrases)
        assertEquals(listOf("pixel", "9a"), run.terms)
    }

    @Test
    fun `an adjacent two-word subject is one phrase`() {
        // "flight ticket" is one of the phrases the spec asks to preserve, not
        // two unrelated words.
        val run = extract("flight ticket")
        assertEquals(listOf("flight ticket"), run.phrases)
        assertEquals(listOf("flight", "ticket"), run.terms)
    }

    @Test
    fun `separated subjects become separate terms`() {
        val run = extract("airpods from amazon")
        assertTrue(run.phrases.isEmpty())
        assertEquals(listOf("airpods", "amazon"), run.terms)
    }

    @Test
    fun `a removed interior word does not break a phrase`() {
        // "saw" is filler; "Pixel 9a" was still adjacent in the original token
        // sequence, so it stays one phrase.
        val run = extract("saw Pixel 9a")
        assertEquals(listOf("pixel 9a"), run.phrases)
    }

    @Test
    fun `a consumed region is not also a search term`() {
        val query = "Pixel 9a ₹39,999"
        val consumed = listOf(QuerySpan(query.indexOf("39,999"), query.length))
        val run = extract(query, consumed)
        assertEquals(listOf("pixel 9a"), run.phrases)
        assertEquals(listOf("pixel", "9a"), run.terms)
    }

    @Test
    fun `bare framing produces nothing at all`() {
        assertTrue(extract("show me the screenshots").isEmpty)
        assertTrue(extract("").isEmpty)
        assertTrue(extract("   ").isEmpty)
    }

    @Test
    fun `a one-character term survives inside a phrase`() {
        val run = extract("iPhone 15")
        assertEquals(listOf("iphone 15"), run.phrases)
        assertEquals(listOf("iphone", "15"), run.terms)
    }

    @Test
    fun `alphanumeric model tokens are kept whole`() {
        val run = extract("9a and 9T Pro and iphone15")
        assertTrue(run.terms.containsAll(listOf("9a", "9t", "iphone15")))
    }

    @Test
    fun `term count is bounded`() {
        val run = extract((1..40).joinToString(" ") { "term$it" })
        assertTrue("term count was ${run.terms.size}", run.terms.size <= 12)
    }

    @Test
    fun `phrases are preserved in the order they appear`() {
        val run = extract("google pixel")
        assertEquals(listOf("google pixel"), run.phrases)
    }

    @Test
    fun `a trailing ask is trimmed but the subject is not`() {
        val run = extract("airpods please")
        assertEquals(listOf("airpods"), run.terms)
    }

    @Test
    fun `screen is not filler so a real phrase survives`() {
        val run = extract("lock screen")
        assertEquals(listOf("lock screen"), run.phrases)
    }
}

/** Explicit structural requests (§30, §40). */
class ContentTypeParserTest {

    private val parser = ContentTypeParser()

    @Test
    fun `duplicate requests become a content type, not a search term`() {
        val result = parser.parse("show duplicate screenshots")
        assertEquals(setOf(ContentType.DUPLICATES), result.types)
        assertEquals("duplicate", "show duplicate screenshots"
            .substring(result.spans.single().start, result.spans.single().end))
    }

    @Test
    fun `near-duplicates are recognised`() {
        assertEquals(
            setOf(ContentType.DUPLICATES),
            parser.parse("find near duplicates").types,
        )
    }

    @Test
    fun `code requests`() {
        assertEquals(setOf(ContentType.OTPS), parser.parse("show my OTPs").types)
    }

    @Test
    fun `link requests`() {
        assertEquals(setOf(ContentType.URLS), parser.parse("screenshots containing a URL").types)
    }

    @Test
    fun `price requests`() {
        assertEquals(setOf(ContentType.PRICES), parser.parse("screenshots with prices").types)
    }

    @Test
    fun `phone requests`() {
        assertEquals(setOf(ContentType.PHONES), parser.parse("screenshots with a phone number").types)
    }

    @Test
    fun `a plain query claims nothing`() {
        val result = parser.parse("find the screenshot of Pixel 9a")
        assertTrue(result.types.isEmpty())
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `an already-claimed region is skipped`() {
        val claimed = listOf(QuerySpan(5, 13))
        val result = parser.parse("show duplicate screenshots", claimed)
        assertTrue(result.types.isEmpty())
    }
}
