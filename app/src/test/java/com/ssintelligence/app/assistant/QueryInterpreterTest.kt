package com.ssintelligence.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent, entity, price and temporal understanding. */
class QueryInterpreterTest {

    private val interpreter = QueryInterpreter

    @Test
    fun `a price question is a price history intent`() {
        val q = interpreter.interpret("What price did I see for the Pixel 9a?", AssistantContext.Empty)
        assertEquals(AssistantIntent.PRICE_HISTORY, q.intent)
        assertEquals("Pixel 9a", q.entityText)
    }

    @Test
    fun `a cheapest question is a price history intent`() {
        val q = interpreter.interpret("What was the cheapest Pixel 9a price I saw?", AssistantContext.Empty)
        assertEquals(AssistantIntent.PRICE_HISTORY, q.intent)
    }

    @Test
    fun `an everything question is an entity lookup`() {
        val q = interpreter.interpret("Show me everything I saved about Japan", AssistantContext.Empty)
        assertEquals(AssistantIntent.ENTITY_LOOKUP, q.intent)
        assertEquals("Japan", q.entityText)
    }

    @Test
    fun `a before question is a timeline intent`() {
        val q = interpreter.interpret("Find the screenshot before the one showing ₹39,999", AssistantContext.Empty)
        assertEquals(AssistantIntent.TIMELINE, q.intent)
    }

    @Test
    fun `a comparison question is a compare intent`() {
        val q = interpreter.interpret("What changed between these two screenshots?", AssistantContext.Empty)
        assertEquals(AssistantIntent.COMPARE, q.intent)
        assertTrue(q.wantsComparison)
    }

    @Test
    fun `a count question is a count intent`() {
        val q = interpreter.interpret("How many screenshots do I have about travel?", AssistantContext.Empty)
        assertEquals(AssistantIntent.COUNT, q.intent)
    }

    @Test
    fun `a vague memory question is a recall intent`() {
        val q = interpreter.interpret("I remember a screenshot about GST with a blue header", AssistantContext.Empty)
        assertEquals(AssistantIntent.RECALL, q.intent)
        assertEquals("blue", q.color)
    }

    @Test
    fun `a website question is a relationship intent`() {
        val q = interpreter.interpret("What websites did I visit for Pixel phones?", AssistantContext.Empty)
        assertEquals(AssistantIntent.RELATIONSHIP, q.intent)
    }

    @Test
    fun `an under question carries a price constraint`() {
        val q = interpreter.interpret("Pixel 9a under ₹40,000", AssistantContext.Empty)
        assertEquals(40000.0, q.priceConstraint?.max ?: 0.0, 0.001)
        assertEquals("INR", q.priceConstraint?.currency)
    }

    @Test
    fun `a between question carries a price range`() {
        val q = interpreter.interpret("Phones between ₹30,000 and ₹45,000", AssistantContext.Empty)
        assertEquals(30000.0, q.priceConstraint?.min ?: 0.0, 0.001)
        assertEquals(45000.0, q.priceConstraint?.max ?: 0.0, 0.001)
    }

    @Test
    fun `last month resolves to a real calendar window`() {
        val q = interpreter.interpret("Pixel screenshots from last month", AssistantContext.Empty)
        val range = requireNotNull(q.dateRange) { "last month produced no window" }
        assertTrue(range.endSeconds > range.startSeconds)
    }

    @Test
    fun `recently resolves to a two week window`() {
        val q = interpreter.interpret("What did I save recently?", AssistantContext.Empty)
        val range = requireNotNull(q.dateRange) { "recently produced no window" }
        val spanDays = (range.endSeconds - range.startSeconds) / 86_400.0
        assertTrue("span was $spanDays days", spanDays in 13.0..15.0)
    }

    @Test
    fun `a pronoun follow up inherits the previous entity`() {
        val context = AssistantContext(
            lastEntity = "Pixel 9a",
            lastPriceFilter = null,
            lastDateRange = null,
            lastEvidenceIds = listOf(1, 2),
            lastIntent = AssistantIntent.ENTITY_LOOKUP,
            lastRequiresReveal = false,
        )
        val q = interpreter.interpret("Which had the lowest price?", context)
        assertEquals("Pixel 9a", q.entityText)
        assertEquals(AssistantIntent.PRICE_HISTORY, q.intent)
    }

    @Test
    fun `a bare it question inherits the previous price filter`() {
        val context = AssistantContext(
            lastEntity = null,
            lastPriceFilter = "INR:39999.0",
            lastDateRange = null,
            lastEvidenceIds = listOf(1),
            lastIntent = AssistantIntent.PRICE_HISTORY,
            lastRequiresReveal = false,
        )
        val q = interpreter.interpret("When did I see it?", context)
        assertTrue(q.refersToPrevious)
        assertEquals("INR:39999.0", q.priceConstraint?.describe())
    }

    @Test
    fun `a domain in the query becomes the entity`() {
        val q = interpreter.interpret("What did I save about amazon.in?", AssistantContext.Empty)
        assertEquals("amazon.in", q.entityText)
    }

    @Test
    fun `a plain search stays a search`() {
        val q = interpreter.interpret("flight tickets", AssistantContext.Empty)
        assertEquals(AssistantIntent.SEARCH, q.intent)
        assertNull(q.entityText)
    }
}
