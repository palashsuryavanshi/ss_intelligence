package com.ssintelligence.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers are assembled from evidence only. */
class LocalResponseGeneratorTest {

    private val evidence = listOf(
        Evidence(
            screenshotId = 1, dateAdded = 1_700_000_000L, filename = "a.png",
            prices = listOf("INR" to 39999.0), hosts = listOf("amazon.in"),
            ocrExcerpt = "Pixel 9a", entities = emptySet(),
            sensitivity = SensitivityLevel.NORMAL, signals = listOf("text match"),
        ),
        Evidence(
            screenshotId = 2, dateAdded = 1_700_0086_400L, filename = "b.png",
            prices = listOf("INR" to 41999.0), hosts = listOf("flipkart.com"),
            ocrExcerpt = "Pixel 9a", entities = emptySet(),
            sensitivity = SensitivityLevel.NORMAL, signals = listOf("text match"),
        ),
    )

    private fun query(intent: AssistantIntent, entity: String? = "Pixel 9a") =
        QueryInterpreter.InterpretedQuery(
            intent = intent, entityText = entity, priceConstraint = null,
            dateRange = null, color = null, wantsComparison = false,
            refersToPrevious = false, keywords = emptyList(), raw = "",
        )

    @Test
    fun `a price answer reports the observed range`() {
        val answer = TemplateResponseGenerator.generate(query(AssistantIntent.PRICE_HISTORY), evidence)
        assertTrue(answer.contains("₹39,999"))
        assertTrue(answer.contains("₹41,999"))
        assertTrue(answer.contains("lowest"))
    }

    @Test
    fun `a count answer states the count`() {
        val answer = TemplateResponseGenerator.generate(query(AssistantIntent.COUNT), evidence)
        assertEquals("2 screenshots matched.", answer)
    }

    @Test
    fun `a timeline answer states the window`() {
        val answer = TemplateResponseGenerator.generate(query(AssistantIntent.TIMELINE), evidence)
        assertTrue(answer.contains("2 screenshots"))
        assertTrue(answer.contains("2023"))
    }

    @Test
    fun `a comparison answer lists the differing prices`() {
        val answer = TemplateResponseGenerator.generate(query(AssistantIntent.COMPARE), evidence)
        assertTrue(answer.contains("₹39,999"))
        assertTrue(answer.contains("₹41,999"))
    }

    @Test
    fun `no evidence yields the honest no-answer`() {
        val answer = LocalResponseGenerator.generate(query(AssistantIntent.SEARCH), emptyList())
        assertTrue(answer.contains("couldn't find"))
    }

    @Test
    fun `sensitive evidence is masked`() {
        val sensitive = evidence + Evidence(
            screenshotId = 3, dateAdded = 1_700_000_000L, filename = "c.png",
            prices = emptyList(), hosts = emptyList(), ocrExcerpt = "bank",
            entities = emptySet(), sensitivity = SensitivityLevel.HIGHLY_SENSITIVE,
            signals = listOf("text match"),
        )
        val answer = LocalResponseGenerator.generate(query(AssistantIntent.SEARCH), sensitive)
        assertTrue(answer.contains("masked"))
    }

    @Test
    fun `a single price answer does not invent a range`() {
        val one = evidence.take(1)
        val answer = TemplateResponseGenerator.generate(query(AssistantIntent.PRICE_HISTORY), one)
        assertTrue(answer.contains("₹39,999"))
        assertTrue(!answer.contains("highest"))
    }
}
