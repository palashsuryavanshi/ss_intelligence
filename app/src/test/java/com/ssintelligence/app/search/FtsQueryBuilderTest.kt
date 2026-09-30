package com.ssintelligence.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FTS query construction (§25).
 */
class FtsQueryBuilderTest {

    @Test
    fun `builds a prefix match expression for a single term`() {
        assertEquals("\"pixel\"*", FtsQueryBuilder.build("pixel"))
    }

    @Test
    fun `ands multiple terms with the implicit operator`() {
        // FTS4 rejects the explicit AND keyword here, so terms are joined by
        // whitespace and AND-ed by the engine.
        assertEquals("\"pixel\"* \"9a\"*", FtsQueryBuilder.build("pixel 9a"))
    }

    @Test
    fun `collapses repeated whitespace`() {
        assertEquals("\"pixel\"* \"9a\"*", FtsQueryBuilder.build("   pixel    9a   "))
    }

    @Test
    fun `strips surrounding punctuation`() {
        assertEquals("\"pixel\"*", FtsQueryBuilder.build("pixel,"))
        assertEquals("\"pixel\"*", FtsQueryBuilder.build("(pixel)"))
    }

    @Test
    fun `ignores terms shorter than two characters`() {
        assertEquals("\"pixel\"*", FtsQueryBuilder.build("a pixel"))
    }

    @Test
    fun `deduplicates repeated terms`() {
        assertEquals("\"pixel\"*", FtsQueryBuilder.build("pixel pixel"))
    }

    @Test
    fun `bounds the number of terms`() {
        val query = (1..30).joinToString(" ") { "term$it" }
        val built = FtsQueryBuilder.build(query)!!
        assertEquals(10, built.split(" ").size)
    }

    @Test
    fun `a term wrapped in quotes is tokenized rather than passed through`() {
        // Surrounding punctuation is stripped before quoting, so a quoted term
        // behaves like any other term instead of becoming an FTS syntax error.
        val built = FtsQueryBuilder.build("""say "hello"""")!!
        assertEquals("\"say\"* \"hello\"*", built)
    }

    @Test
    fun `returns null when there is nothing to search for`() {
        assertNull(FtsQueryBuilder.build(""))
        assertNull(FtsQueryBuilder.build("   "))
        assertNull(FtsQueryBuilder.build("a"))
        assertNull(FtsQueryBuilder.build("..."))
    }

    @Test
    fun `escapes like wildcards`() {
        assertEquals("100\\%", FtsQueryBuilder.escapeLike("100%"))
        assertEquals("a\\_b", FtsQueryBuilder.escapeLike("a_b"))
        assertEquals("back\\\\slash", FtsQueryBuilder.escapeLike("back\\slash"))
    }
}
