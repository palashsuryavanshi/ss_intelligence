package com.ssintelligence.app.graph

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entity normalization: conservative merging, ambiguity respected. */
class EntityNormalizationTest {

    @Test
    fun `product variants normalize together`() {
        // Case, spacing and joined letters collapse; a brand prefix stays part
        // of the name (conservative: "Google Pixel 9a" does not merge with
        // "Pixel 9a" without more evidence).
        assertEquals("pixel 9a", EntityNormalization.product("Pixel 9a"))
        assertEquals("pixel 9a", EntityNormalization.product("PIXEL 9A"))
        assertEquals("pixel 9a", EntityNormalization.product("Pixel9a"))
        assertEquals("pixel 9a", EntityNormalization.product("  pixel   9a "))
        assertEquals("google pixel 9a", EntityNormalization.product("Google Pixel 9a"))
    }

    @Test
    fun `letter digit boundaries split but model suffixes stay intact`() {
        assertEquals("pixel 9a", EntityNormalization.product("Pixel9A"))
        assertEquals("oneplus 13", EntityNormalization.product("OnePlus13"))
    }

    @Test
    fun `hosts normalize without www`() {
        assertEquals("amazon.in", EntityNormalization.website("WWW.Amazon.In"))
        assertEquals("amazon.in", EntityNormalization.website("amazon.in."))
    }

    @Test
    fun `company strips subdomains but keeps country domains`() {
        assertEquals("amazon", EntityNormalization.company("smile.amazon.in"))
        assertEquals("amazon", EntityNormalization.company("www.amazon.in"))
        assertEquals("bbc", EntityNormalization.company("www.bbc.co.uk"))
        assertEquals("flipkart", EntityNormalization.company("flipkart.com"))
    }

    @Test
    fun `prices never convert`() {
        assertTrue(EntityNormalization.price("USD", 400.0) != EntityNormalization.price("INR", 400.0))
    }
}

/** Graph building is grounded: only filed, never invented. */
class GraphBuilderTest {

    private fun input() = GraphBuilder.Input(
        phrases = listOf("Pixel 9a", "Pixel 9a"),
        hosts = listOf("www.amazon.in"),
        prices = listOf(GraphBuilder.PriceInput("INR", 39999.0, "₹39,999")),
        dateTexts = listOf("28 Sep 2026"),
        orderCodes = listOf("ABC123"),
        bookingCodes = emptyList(),
        categories = listOf("Shopping"),
    )

    @Test
    fun `a listing files product price website company and category`() {
        val relations = GraphBuilder.build(input())
        val kinds = relations.map { it.kind }.toSet()
        assertTrue(kinds.contains(RelationKind.MENTIONS))
        assertTrue(kinds.contains(RelationKind.PRICED_AT))
        assertTrue(kinds.contains(RelationKind.FOUND_ON))
        assertTrue(kinds.contains(RelationKind.SOLD_BY))
        assertTrue(kinds.contains(RelationKind.BELONGS_TO))
        assertTrue(kinds.contains(RelationKind.OCCURRED_ON))
    }

    @Test
    fun `duplicate phrases file once`() {
        val relations = GraphBuilder.build(input())
        assertEquals(1, relations.count { it.entity.type == GraphEntityType.PRODUCT })
    }

    @Test
    fun `short phrases are skipped`() {
        val relations = GraphBuilder.build(input().copy(phrases = listOf("a", "9")))
        assertTrue(relations.none { it.entity.type == GraphEntityType.PRODUCT })
    }

    @Test
    fun `hosts without a dot are skipped`() {
        val relations = GraphBuilder.build(input().copy(hosts = listOf("localhost")))
        assertTrue(relations.none { it.entity.type == GraphEntityType.WEBSITE })
    }

    @Test
    fun `entity counts stay bounded`() {
        val many = (1..50).map { "Product number $it variant" }
        val relations = GraphBuilder.build(input().copy(phrases = many))
        assertTrue(relations.count { it.entity.type == GraphEntityType.PRODUCT } <= 8)
    }

    /**
     * Found on the device: with no product gate, every frequent OCR word became
     * a PRODUCT entity — `post` on 10 screenshots, `search` on 9, `data` on 7 —
     * which made Explore and the Home suggestion meaningless.
     */
    @Test
    fun `frequent web chrome is not filed as a product`() {
        val chrome = listOf(
            "post", "search", "data", "high", "follow", "protection", "trackers",
            "settings", "history", "court", "likes", "reviews", "privacy", "cookies",
        )
        val relations = GraphBuilder.build(input().copy(phrases = chrome))
        assertTrue(
            "no product entities expected, got " +
                relations.filter { it.entity.type == GraphEntityType.PRODUCT }
                    .map { it.entity.displayName },
            relations.none { it.entity.type == GraphEntityType.PRODUCT },
        )
    }

    @Test
    fun `a model number is filed as a product`() {
        val relations = GraphBuilder.build(input().copy(phrases = listOf("Pixel 9a", "OnePlus 13")))
        val products = relations.filter { it.entity.type == GraphEntityType.PRODUCT }
        assertEquals(2, products.size)
        assertEquals(setOf("pixel 9a", "oneplus 13"), products.map { it.entity.normalizedName }.toSet())
    }

    @Test
    fun `a product noun is filed without a model number`() {
        val relations = GraphBuilder.build(input().copy(phrases = listOf("earbuds", "Laptop")))
        assertEquals(2, relations.count { it.entity.type == GraphEntityType.PRODUCT })
    }

    @Test
    fun `a product noun buried in chrome still counts`() {
        // "cover" is both a product noun and a common word; the noun wins here
        // because the phrase is explicitly about the object.
        assertTrue(GraphBuilder.looksLikeProduct("Phone cover"))
        assertTrue(GraphBuilder.looksLikeProduct("wireless earbuds"))
    }

    @Test
    fun `long phrases and pure numbers are not products`() {
        assertTrue(!GraphBuilder.looksLikeProduct("this is a long sentence that is not a thing"))
        assertTrue(!GraphBuilder.looksLikeProduct("39999"))
        assertTrue(!GraphBuilder.looksLikeProduct("12345"))
    }
}

/** Ambiguity: Apple the word is not Apple the company. */
class EntityAmbiguityTest {

    @Test
    fun `a bare apple word creates no company`() {
        // Companies come from hosts only. The builder takes hosts, not words,
        // so "I ate an apple" can never file Apple Inc.
        val relations = GraphBuilder.build(
            GraphBuilder.Input(
                phrases = listOf("apple pie recipe"),
                hosts = emptyList(),
                prices = emptyList(),
                dateTexts = emptyList(),
                orderCodes = emptyList(),
                bookingCodes = emptyList(),
                categories = emptyList(),
            ),
        )
        assertTrue(relations.none { it.entity.type == GraphEntityType.COMPANY })
    }

    @Test
    fun `an apple host creates the company`() {
        val relations = GraphBuilder.build(
            GraphBuilder.Input(
                phrases = emptyList(),
                hosts = listOf("www.apple.com"),
                prices = emptyList(),
                dateTexts = emptyList(),
                orderCodes = emptyList(),
                bookingCodes = emptyList(),
                categories = emptyList(),
            ),
        )
        val company = relations.firstOrNull { it.entity.type == GraphEntityType.COMPANY }
        assertEquals("apple", company?.entity?.normalizedName)
    }
}
