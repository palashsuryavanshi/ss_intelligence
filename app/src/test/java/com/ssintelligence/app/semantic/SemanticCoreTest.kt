package com.ssintelligence.app.semantic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Deterministic embeddings: stability, normalization, typo tolerance. */
class HashedNgramEmbeddingProviderTest {

    private val provider = HashedNgramEmbeddingProvider()

    @Test
    fun `vectors are L2 normalized`() = runBlocking {
        val embedding = provider.embed("Google Pixel 9a 5G")
        var sum = 0.0
        for (value in embedding.values) sum += value * value
        assertEquals(1.0, sum, 0.001)
    }

    @Test
    fun `identical text has similarity one`() = runBlocking {
        val a = provider.embed("flight ticket to Mumbai")
        val b = provider.embed("flight ticket to Mumbai")
        assertEquals(1.0, a.similarityTo(b), 0.001)
    }

    @Test
    fun `empty text embeds to the zero vector`() = runBlocking {
        val embedding = provider.embed("   ")
        assertTrue(embedding.values.all { it == 0f })
    }

    @Test
    fun `an OCR typo still matches closely`() = runBlocking {
        val clean = provider.embed("Google Pixel 9a smartphone")
        val typo = provider.embed("G00gle Pixcl 9a smartph0ne")
        val unrelated = provider.embed("hospital appointment prescription")
        assertTrue(typo.similarityTo(clean) > unrelated.similarityTo(clean))
        assertTrue(typo.similarityTo(clean) > 0.5)
    }

    @Test
    fun `shared topic outranks unrelated text`() = runBlocking {
        val query = provider.embed("phone deal discount offer")
        val phone = provider.embed("Pixel 9a smartphone price offer")
        val flight = provider.embed("boarding pass departure gate")
        assertTrue(phone.similarityTo(query) > flight.similarityTo(query))
    }

    @Test
    fun `embeddings are deterministic across calls`() = runBlocking {
        assertEquals(provider.embed("Pixel 9a ₹39,999"), provider.embed("Pixel 9a ₹39,999"))
    }

    @Test
    fun `dimension is configurable`() {
        assertEquals(128, HashedNgramEmbeddingProvider(dimension = 128).dimension)
    }

    @Test
    fun `mixing model versions is refused`() {
        val a = runBlocking { provider.embed("hello") }
        val other = runBlocking { provider.embed("hello") }.copy(version = "999")
        try {
            a.similarityTo(other)
            fail("expected IllegalArgumentException for mixed embedding versions")
        } catch (error: IllegalArgumentException) {
            // Expected: old embeddings must be rebuilt, never mixed.
        }
    }

    @Test
    fun `metadata identifies the provider`() {
        assertEquals("hashed-ngram", provider.model)
        assertEquals("1", provider.version)
        assertTrue(provider.isAvailable)
    }
}

/** Concept expansion: bounded, reviewable, never rewriting the query. */
class ConceptQueryExpanderTest {

    private val expander = ConceptQueryExpander()

    @Test
    fun `travel expands to bookings and tickets`() {
        val expanded = expander.expandTerms(listOf("travel", "booking"))
        assertTrue(expanded.contains("flight"))
        assertTrue(expanded.contains("hotel"))
        assertTrue(expanded.contains("ticket"))
    }

    @Test
    fun `a direct concept mention wins over a vague one`() {
        val concepts = expander.conceptsForQuery(listOf("flight", "ticket"))
        assertEquals("flight", concepts.first())
    }

    @Test
    fun `expansion never repeats the query terms`() {
        val expanded = expander.expandTerms(listOf("travel"))
        assertTrue("travel" !in expanded)
    }

    @Test
    fun `expansion is bounded`() {
        val expanded = expander.expandTerms(listOf("travel", "shopping", "finance", "food", "work"))
        assertTrue(expanded.size <= 12)
    }

    @Test
    fun `unknown words expand to nothing`() {
        assertTrue(expander.expandTerms(listOf("xyzzy", "plugh")).isEmpty())
        assertTrue(expander.conceptsForQuery(listOf("xyzzy")).isEmpty())
    }

    @Test
    fun `phone deal touches the right concepts`() {
        val concepts = expander.conceptsForQuery(listOf("phone", "deal"))
        assertTrue(concepts.contains("phone") || concepts.contains("deal"))
    }
}

/** Hybrid scoring: the exact-match guarantee is arithmetic, not a special case. */
class HybridRankerTest {

    private val ranker = HybridRanker()

    @Test
    fun `an exact match always outranks a semantic-only match`() {
        val exact = ranker.score(HybridSignals(lexical = 100.0, semantic = 0.9, metadata = 1.0))
        val semanticOnly = ranker.score(HybridSignals(lexical = 0.0, semantic = 1.0, metadata = 0.0))
        assertTrue("exact=$exact semanticOnly=$semanticOnly", exact > semanticOnly)
    }

    @Test
    fun `exact match floor holds under any re-tuning that keeps lexical dominant`() {
        // If someone re-tunes the weights, this documents the invariant that
        // must survive: lexical + metadata at full must beat every soft
        // signal at full combined.
        val weights = HybridWeights.Default
        val exactFloor = 100.0 * weights.lexical + 100.0 * weights.metadata
        val softCeiling = 100.0 * (weights.semantic + weights.visual + weights.entity)
        assertTrue(exactFloor > softCeiling)
    }

    @Test
    fun `semantics breaks ties between equal lexical matches`() {
        val a = ranker.score(HybridSignals(lexical = 50.0, semantic = 0.9, metadata = 0.5))
        val b = ranker.score(HybridSignals(lexical = 50.0, semantic = 0.2, metadata = 0.5))
        assertTrue(a > b)
    }

    @Test
    fun `scores stay in range`() {
        assertEquals(100.0, ranker.score(HybridSignals(100.0, 1.0, 1.0, 1.0, 1.0)), 0.001)
        assertEquals(0.0, ranker.score(HybridSignals(0.0, 0.0, 0.0)), 0.001)
        // Partial signals land proportionally between the bounds.
        val partial = ranker.score(HybridSignals(100.0, 1.0, 1.0))
        assertTrue(partial > 0 && partial < 100.0)
    }

    @Test
    fun `lexical normalization caps an exceptional score`() {
        assertEquals(100.0, ranker.normalizeLexical(9999), 0.001)
        assertEquals(0.0, ranker.normalizeLexical(0), 0.001)
    }
}
