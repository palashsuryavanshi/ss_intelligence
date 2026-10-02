package com.ssintelligence.app.autonomous

import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.semantic.ScreenshotDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Topic, session, event, importance, lifecycle and suggestion detection. */
class AutonomousAnalysisEngineTest {

    private val engine = AutonomousAnalysisEngine()

    private fun screenshot(
        id: Long,
        ocr: String,
        dateAdded: Long = 1_700_000_000L,
        fileSize: Long = 100_000,
    ) = Screenshot(
        id = id, mediaStoreId = id, uri = "content://x/$id", filename = "s$id.png",
        relativePath = null, dateAdded = dateAdded, dateModified = dateAdded,
        fileSize = fileSize, width = 1080, height = 2400, mimeType = "image/png",
        ocrText = ocr, contentHash = "h$id", duplicateOfId = null,
        status = ProcessingStatus.COMPLETED, error = null, createdAt = 0, updatedAt = 0,
    )

    private fun input(
        id: Long,
        ocr: String,
        entities: Set<String> = emptySet(),
        dateAdded: Long = 1_700_000_000L,
        fileSize: Long = 100_000,
        neighbours: List<AutonomousAnalysisEngine.Input.Neighbour> = emptyList(),
    ) = AutonomousAnalysisEngine.Input(
        screenshot = screenshot(id, ocr, dateAdded, fileSize),
        document = ScreenshotDocument(id, ocr, "s$id.png"),
        entities = entities,
        visualType = null,
        visualLayout = null,
        isDark = false,
        colors = emptyList(),
        neighbours = neighbours,
    )

    @Test
    fun `a topic emerges from the screenshot's own entity`() {
        val topic = engine.detectTopic(input(1, "Pixel 9a deal", entities = setOf("Pixel 9a")))
        assertEquals("Pixel 9a", topic.subject)
        assertTrue(topic.signals.any { it.contains("entities") })
    }

    @Test
    fun `a topic falls back to the first OCR line`() {
        val topic = engine.detectTopic(input(1, "Flight ticket booking"))
        assertEquals("Flight ticket booking", topic.subject)
    }

    @Test
    fun `a confirmation is a confirmed booking event`() {
        val event = engine.detectEvent(input(1, "Booking confirmed PNR 7QK2LP"))
        assertNotNull(event)
        assertEquals(EventType.BOOKING, event!!.type)
        assertEquals(EventConfidence.CONFIRMED, event.confidence)
    }

    @Test
    fun `a checkout without confirmation is only possible`() {
        val event = engine.detectEvent(input(1, "Checkout pay now"))
        assertNotNull(event)
        assertEquals(EventType.PURCHASE, event!!.type)
        assertEquals(EventConfidence.POSSIBLE, event.confidence)
    }

    @Test
    fun `a bare search is not an event`() {
        val event = engine.detectEvent(input(1, "search results"))
        assertNull(event)
    }

    @Test
    fun `a confirmation screenshot is important`() {
        val importance = engine.scoreImportance(input(1, "Booking confirmed ticket"))
        assertEquals(ImportanceLevel.IMPORTANT, importance)
    }

    @Test
    fun `a bare search is routine`() {
        val importance = engine.scoreImportance(input(1, "search results page"))
        assertEquals(ImportanceLevel.ROUTINE, importance)
    }

    @Test
    fun `a fresh screenshot is new`() {
        val lifecycle = engine.analyzeLifecycle(input(1, "Pixel 9a"), nowSeconds = 1_700_000_100)
        assertEquals(LifecycleState.NEW, lifecycle)
    }

    @Test
    fun `an otp screenshot is stale`() {
        val lifecycle = engine.analyzeLifecycle(input(1, "Your OTP is 483921"), nowSeconds = 1_700_000_100)
        assertEquals(LifecycleState.STALE, lifecycle)
    }

    @Test
    fun `an old screenshot is stale`() {
        val lifecycle = engine.analyzeLifecycle(
            input(1, "Pixel 9a", dateAdded = 1_600_000_000),
            nowSeconds = 1_700_000_000,
        )
        assertEquals(LifecycleState.STALE, lifecycle)
    }

    @Test
    fun `a screenshot near a neighbour forms a session`() {
        val neighbour = AutonomousAnalysisEngine.Input.Neighbour(
            screenshot = screenshot(2, "Pixel 9a", dateAdded = 1_700_000_000 + 600),
            entities = setOf("Pixel 9a"),
            hosts = listOf("amazon.in"),
            visualType = "PRODUCT_LISTING",
        )
        val session = engine.detectSession(
            input(1, "Pixel 9a", entities = setOf("Pixel 9a"), neighbours = listOf(neighbour)),
        )
        assertNotNull(session)
        assertEquals(2, session!!.screenshotIds.size)
    }

    @Test
    fun `a screenshot far from any neighbour is not a session`() {
        val neighbour = AutonomousAnalysisEngine.Input.Neighbour(
            screenshot = screenshot(2, "Pixel 9a", dateAdded = 1_700_000_000 + 86_400),
            entities = setOf("Pixel 9a"),
            hosts = emptyList(),
            visualType = null,
        )
        val session = engine.detectSession(
            input(1, "Pixel 9a", entities = setOf("Pixel 9a"), neighbours = listOf(neighbour)),
        )
        assertNull(session)
    }

    @Test
    fun `an otp screenshot is suggested as temporary`() {
        val suggestions = engine.suggest(input(1, "Your OTP is 483921"), nowSeconds = 1_700_000_100)
        assertTrue(suggestions.any { it.kind == SuggestionKind.TEMPORARY })
    }

    @Test
    fun `a large screenshot is suggested`() {
        val suggestions = engine.suggest(input(1, "Pixel 9a", fileSize = 20_000_000), nowSeconds = 1_700_000_100)
        assertTrue(suggestions.any { it.kind == SuggestionKind.LARGE_FILE })
    }

    @Test
    fun `a normal screenshot gets no suggestions`() {
        val suggestions = engine.suggest(input(1, "Pixel 9a deal"), nowSeconds = 1_700_000_100)
        assertTrue(suggestions.isEmpty())
    }
}
