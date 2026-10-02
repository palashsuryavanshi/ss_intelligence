package com.ssintelligence.app.assistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssintelligence.app.data.database.ExtractedPriceEntity
import com.ssintelligence.app.data.database.ExtractedUrlEntity
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.data.repository.SemanticRepositoryImpl
import com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.graph.GraphRepositoryImpl
import com.ssintelligence.app.search.LocalSearchEngine
import com.ssintelligence.app.semantic.HashedNgramEmbeddingProvider
import com.ssintelligence.app.semantic.ScreenshotDocument
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The assistant pipeline against a real database: retrieval, ranking, evidence
 * and validation end to end.
 *
 * The point of these tests is grounding — an answer may only cite screenshots
 * that exist and prices that were actually extracted.
 */
@RunWith(AndroidJUnit4::class)
class AssistantPipelineInstrumentedTest {

    private lateinit var database: SsIntelligenceDatabase
    private lateinit var assistant: ScreenshotAssistant
    private lateinit var engine: LocalSearchEngine

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SsIntelligenceDatabase::class.java,
        ).build()
        val semantic = SemanticRepositoryImpl(
            database = database,
            dao = database.screenshotDao(),
            semanticDao = database.semanticDao(),
            provider = HashedNgramEmbeddingProvider(),
        )
        val graph = GraphRepositoryImpl(
            database.graphDao(),
            database.screenshotDao(),
            database.semanticDao(),
        )
        val engine = LocalSearchEngine(
            dao = database.screenshotDao(),
            history = NoHistory,
            semanticRepository = semantic,
            visualDao = database.visualDao(),
            graphRepository = graph,
        )
        this.engine = engine
        val repository = ScreenshotRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            database,
            database.screenshotDao(),
        )
        repository.semanticRepository = semantic
        repository.graphRepository = graph
        assistant = ScreenshotAssistant(
            retriever = AssistantRetriever(engine, repository, graph),
            ranker = EvidenceRanker(),
            contextBuilder = EvidenceContextBuilder(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(
        ocr: String,
        filename: String,
        price: Pair<Double, String>? = null,
        host: String? = null,
        daysAgo: Long = 0,
    ): Long {
        val dao = database.screenshotDao()
        val added = nowSeconds() - daysAgo * 86_400
        val id = dao.insertIgnoring(
            listOf(
                ScreenshotEntity(
                    mediaStoreId = idCounter++,
                    uri = "content://media/external/images/media/x",
                    filename = filename,
                    relativePath = "Pictures/Screenshots",
                    dateAdded = added,
                    dateModified = added,
                    fileSize = 100_000,
                    width = 1080,
                    height = 2400,
                    mimeType = "image/png",
                    ocrText = ocr,
                    contentHash = "hash-$filename",
                    duplicateOfId = null,
                    status = "COMPLETED",
                    processingError = null,
                    createdAt = added * 1000,
                    updatedAt = added * 1000,
                ),
            ),
        )[0]
        price?.let { dao.insertPrices(listOf(ExtractedPriceEntity(0, id, "x", it.second, it.first))) }
        host?.let { dao.insertUrls(listOf(ExtractedUrlEntity(0, id, "https://$it/p", it))) }
        return id
    }

    private var idCounter = 1L

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    @Test
    fun aPriceQuestionFindsTheCheapestAndCitesIt() = runBlocking {
        val cheap = seed("Pixel 9a ₹39,999", "cheap.png", price = 39999.0 to "INR", host = "amazon.in")
        seed("Pixel 9a ₹41,999", "dear.png", price = 41999.0 to "INR", host = "flipkart.com")

        val turn = assistant.ask("What was the cheapest Pixel 9a price I saw?", AssistantContext.Empty)

        assertTrue(turn.response.answer.contains("₹39,999"))
        assertTrue(turn.response.answer.contains("lowest"))
        assertTrue(turn.response.sources.any { it.screenshotId == cheap })
        assertTrue(turn.response.evidenceChain.validationPassed)
    }

    @Test
    fun anEntityQuestionResolvesThroughTheGraph() = runBlocking {
        val a = seed("Pixel 9a deal", "a.png", price = 39999.0 to "INR", host = "amazon.in")
        seed("Flight ticket", "b.png")
        val graph = GraphRepositoryImpl(database.graphDao(), database.screenshotDao(), database.semanticDao())
        graph.fileScreenshot(
            a,
            ScreenshotDocument(a, "Pixel 9a deal", "a.png", hosts = listOf("amazon.in")),
            listOf("Pixel 9a"),
            emptyList(),
        )

        val turn = assistant.ask("What did I save about Pixel 9a?", AssistantContext.Empty)

        assertTrue(turn.response.answer.contains("Pixel 9a"))
        assertTrue(turn.response.sources.any { it.screenshotId == a })
    }

    @Test
    fun aQuestionWithNoEvidenceSaysSoHonestly() = runBlocking {
        seed("Flight ticket", "b.png")

        val turn = assistant.ask("What did I save about Japan?", AssistantContext.Empty)

        assertTrue(turn.response.answer.contains("couldn't find"))
        assertTrue(turn.response.sources.isEmpty())
        assertEquals(ConfidenceType.NO_MATCH, turn.response.confidenceType)
    }

    @Test
    fun aMultiTurnFollowUpResolvesThePreviousEntity() = runBlocking {
        seed("Pixel 9a ₹39,999", "a.png", price = 39999.0 to "INR", host = "amazon.in")
        seed("Pixel 9a ₹41,999", "b.png", price = 41999.0 to "INR", host = "flipkart.com")

        val first = assistant.ask("Find Pixel 9a screenshots", AssistantContext.Empty)
        val second = assistant.ask("Which had the lowest price?", first.context)

        assertTrue(second.response.answer.contains("₹39,999"))
        assertTrue(second.response.evidenceChain.entity == "Pixel 9a")
    }

    @Test
    fun anOtpQuestionIsMaskedAndRequiresReveal() = runBlocking {
        seed("Your OTP is 483921 for HDFC Bank", "otp.png")

        val turn = assistant.ask("What was the OTP?", AssistantContext.Empty)

        assertTrue(turn.response.requiresReveal)
        assertFalse(turn.response.answer.contains("483921"))
    }

    @Test
    fun aTemporalQuestionUsesTheRealWindow() = runBlocking {
        seed("Pixel 9a ₹39,999", "old.png", price = 39999.0 to "INR", daysAgo = 400)
        seed("Pixel 9a ₹41,999", "new.png", price = 41999.0 to "INR", daysAgo = 1)

        val turn = assistant.ask("Pixel 9a prices from last month", AssistantContext.Empty)

        assertTrue(turn.response.evidenceChain.dateRange != null)
        // The 400-day-old screenshot is outside the window.
        assertTrue(turn.response.sources.none { it.filename == "old.png" })
    }

    private object NoHistory : com.ssintelligence.app.domain.repository.SearchHistoryRepository {
        override suspend fun isEnabled(): Boolean = false
        override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<String>())
        override suspend fun record(query: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun count(): Int = 0
    }
}
