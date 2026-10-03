package com.ssintelligence.app.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssintelligence.app.data.database.ExtractedPriceEntity
import com.ssintelligence.app.data.database.ExtractedUrlEntity
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.data.repository.SemanticRepositoryImpl
import com.ssintelligence.app.domain.model.IndexingScope
import com.ssintelligence.app.domain.model.ThemeMode
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.domain.repository.ProcessingMode
import com.ssintelligence.app.domain.repository.SettingsRepository
import com.ssintelligence.app.semantic.HashedNgramEmbeddingProvider
import com.ssintelligence.app.semantic.ScreenshotCategory
import com.ssintelligence.app.semantic.ScreenshotDocument
import com.ssintelligence.app.semantic.SemanticRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The hybrid engine against a real database (§53, §54, §55).
 *
 * Uses the §53 benchmark library: six screenshots covering phones, travel,
 * tax and laptops. Every assertion is a *relationship* (A before B, A present
 * and F absent), never a hardcoded score — scores are an implementation detail
 * and the tests must survive a re-tuning of the weights (§55).
 */
@RunWith(AndroidJUnit4::class)
class LocalSemanticSearchInstrumentedTest {

    private lateinit var database: SsIntelligenceDatabase
    private lateinit var semantic: SemanticRepository
    private lateinit var engine: LocalSearchEngine
    private var mediaCounter = 0L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SsIntelligenceDatabase::class.java,
        ).build()
        semantic = SemanticRepositoryImpl(
            database = database,
            dao = database.screenshotDao(),
            semanticDao = database.semanticDao(),
            provider = HashedNgramEmbeddingProvider(),
        )
        engine = LocalSearchEngine(
            dao = database.screenshotDao(),
            history = NoHistory,
            semanticRepository = semantic,
            semanticSettings = EnabledSettings,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------ fixtures

    private fun nowSeconds(): Long =
        LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().epochSecond

    private suspend fun seed(
        ocr: String,
        filename: String,
        price: Pair<Double, String>? = null,
        host: String? = null,
    ): Long {
        val dao = database.screenshotDao()
        val added = nowSeconds()
        val id = dao.insertIgnoring(
            listOf(
                ScreenshotEntity(
                    mediaStoreId = ++mediaCounter,
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
        host?.let { dao.insertUrls(listOf(ExtractedUrlEntity(0, id, "https://$host/p", host))) }
        semantic.indexScreenshot(
            ScreenshotDocument(
                screenshotId = id,
                ocrText = ocr,
                filename = filename,
                hosts = listOfNotNull(host),
                prices = listOfNotNull(
                    price?.let {
                        com.ssintelligence.app.domain.model.ExtractedPrice(0, id, "x", it.second, it.first)
                    },
                ),
            ),
        )
        return id
    }

    /**
     * The §53 benchmark library.
     *
     * Worded the way real OCR reads: listings carry "smartphone", "deal" and
     * "offer", bookings carry "confirmation". Sparse, keyword-free fixtures
     * would prove nothing — a semantic test must give the engine words a real
     * screenshot would contain.
     */
    private suspend fun seedBenchmark(): Map<String, Long> = mapOf(
        "A" to seed("Google Pixel 9a smartphone deal 5G 128GB ₹39,999 Flat offer Free delivery", "a.png", 39999.0 to "INR", "amazon.in"),
        "B" to seed("Google Pixel 9 Pro smartphone offer 5G 256GB ₹59,999 Free delivery", "b.png", 59999.0 to "INR", "amazon.in"),
        "C" to seed("Flight ticket booking confirmation Delhi to Mumbai PNR 7QK2LP departure 08:40", "c.png"),
        "D" to seed("Hotel reservation booking confirmation Mumbai checkin Friday checkout Sunday", "d.png"),
        "E" to seed("GST return filing income tax turnover FY 2025-26", "e.png"),
        "F" to seed("Laptop purchase bill Dell Inspiron invoice ₹65,000", "f.png", 65000.0 to "INR"),
    )

    private fun resultsFor(query: String): SearchResponse =
        runBlocking { engine.search(SearchRequest(query)) }

    private fun filenames(response: SearchResponse): List<String> =
        response.results.map { it.screenshot.filename }

    // ---------------------------------------------------- semantic examples

    @Test
    fun phoneDealFindsPhoneListingsByMeaning() = runBlocking {
        val ids = seedBenchmark()
        val response = resultsFor("phone deal")

        val names = filenames(response)
        assertTrue("Pixel listing missing from $names", "a.png" in names)
        // The tax return and the flight ticket are not phone deals.
        assertTrue("e.png" !in names)
        assertTrue("c.png" !in names)
        assertTrue(response.semanticUsed)
    }

    @Test
    fun travelBookingFindsFlightsAndHotels() = runBlocking {
        seedBenchmark()
        val response = resultsFor("travel booking")

        val names = filenames(response)
        assertTrue("flight ticket missing from $names", "c.png" in names)
        assertTrue("hotel missing from $names", "d.png" in names)
        assertTrue("e.png" !in names)
    }

    @Test
    fun hotelReservationFindsTheHotelFirst() = runBlocking {
        seedBenchmark()
        val response = resultsFor("hotel reservation")

        assertEquals("d.png", response.results.first().screenshot.filename)
    }

    @Test
    fun taxWorkFindsTheGstScreenshot() = runBlocking {
        seedBenchmark()
        val response = resultsFor("tax work")

        val names = filenames(response)
        assertTrue("e.png" in names)
        assertTrue("c.png" !in names)
    }

    @Test
    fun laptopPurchaseFindsTheLaptop() = runBlocking {
        seedBenchmark()
        val response = resultsFor("laptop purchase")

        assertEquals("f.png", response.results.first().screenshot.filename)
    }

    // ------------------------------------------------------- hybrid (§54)

    @Test
    fun exactMatchOutranksNearMatchOutranksSibling() = runBlocking {
        seedBenchmark()
        // ₹40,999 with the same phrase: inside the relaxed band, outside an
        // exact-price query — the "near" case from §54.
        seed("Google Pixel 9a smartphone 5G ₹40,999", "a2.png", 40999.0 to "INR", "amazon.in")

        // An exact query admits only the exact row: exactness dominates.
        val exact = resultsFor("Pixel 9a ₹39,999")
        assertEquals("a.png", exact.results.first().screenshot.filename)

        // Without a price, the phrase beats scattered words: both Pixel 9a
        // rows precede the 9 Pro sibling that shares only "Pixel". The two
        // 9a rows are equivalent matches with no price to separate them, so
        // their relative order is asserted as a set, not a sequence.
        val words = resultsFor("Pixel 9a")
        val names = filenames(words)
        assertEquals(setOf("a.png", "a2.png"), names.take(2).toSet())
        assertEquals("b.png", names[2])

        // A query nothing matches exactly relaxes to the ±5% band, and the
        // relaxed rung says so.
        val relaxed = engine.search(SearchRequest("Pixel 9a ₹39,998"))
        assertEquals(RelaxationLevel.PRICE_APPROXIMATE, relaxed.relaxation)
        val relaxedNames = filenames(relaxed)
        val exactIndex = relaxedNames.indexOf("a.png")
        val nearIndex = relaxedNames.indexOf("a2.png")
        assertTrue("exact missing from $relaxedNames", exactIndex >= 0)
        assertTrue("near missing from $relaxedNames", nearIndex >= 0)
        assertTrue("exact ($exactIndex) must precede near ($nearIndex)", exactIndex < nearIndex)
    }

    @Test
    fun semanticSignalNeverOutranksAnExactMatch() = runBlocking {
        seedBenchmark()
        // A paraphrase with no shared keywords: pure semantic bait.
        seed("Google smartphone deal offer discount sale", "bait.png")

        val response = resultsFor("Pixel 9a ₹39,999")

        assertEquals("a.png", response.results.first().screenshot.filename)
    }

    // ------------------------------------------------- related and similar

    @Test
    fun findSimilarReturnsSameFamilyScreenshots() = runBlocking {
        val ids = seedBenchmark()
        val document = ScreenshotDocument(
            screenshotId = ids.getValue("A"),
            ocrText = "Google Pixel 9a 5G 128GB ₹39,999 Free delivery",
            filename = "a.png",
            hosts = listOf("amazon.in"),
        )
        val similar = semantic.findSimilarTo(document)

        val similarIds = similar.map { it.screenshotId }
        assertTrue("Pixel 9 Pro should resemble Pixel 9a: $similarIds", ids.getValue("B") in similarIds)
        assertTrue(ids.getValue("E") !in similarIds)
        assertTrue(ids.getValue("C") !in similarIds)
    }

    @Test
    fun categoriesAreStoredForIndexedScreenshots() = runBlocking {
        val ids = seedBenchmark()
        val categories = semantic.categoriesFor(ids.getValue("C")).map { it.category }
        assertTrue("FLIGHTS in $categories", ScreenshotCategory.FLIGHTS in categories)
    }

    @Test
    fun userCorrectionSurvivesReindexing() = runBlocking {
        val ids = seedBenchmark()
        val id = ids.getValue("C")
        semantic.setUserCategory(id, ScreenshotCategory.WORK)
        // Re-indexing replaces auto rows only.
        semantic.indexScreenshot(
            ScreenshotDocument(id, "Flight ticket PNR", "c.png"),
        )
        val categories = semantic.categoriesFor(id).map { it.category }
        assertTrue("user override lost: $categories", ScreenshotCategory.WORK in categories)
    }

    // ------------------------------------------------------------- fallback

    @Test
    fun disabledSemanticSearchStillReturnsLexicalResults() = runBlocking {
        seedBenchmark()
        val lexicalOnly = LocalSearchEngine(
            dao = database.screenshotDao(),
            history = NoHistory,
            semanticRepository = semantic,
            semanticSettings = DisabledSettings,
        )
        val response = lexicalOnly.search(SearchRequest("Pixel"))

        assertFalse(response.semanticUsed)
        assertTrue(response.results.any { it.screenshot.filename == "a.png" })
    }

    @Test
    fun missingSemanticIndexDegradesGracefully() = runBlocking {
        seedBenchmark()
        val noSemantic = LocalSearchEngine(
            dao = database.screenshotDao(),
            history = NoHistory,
            semanticRepository = null,
        )
        val response = noSemantic.search(SearchRequest("Pixel 9a"))

        assertFalse(response.semanticUsed)
        assertTrue(response.results.isNotEmpty())
    }

    // ---------------------------------------------------- duplicate collapse

    @Test
    fun identicalScreenshotsCollapseIntoOneResult() = runBlocking {
        val dao = database.screenshotDao()
        val added = nowSeconds()
        val first = dao.insertIgnoring(
            listOf(
                ScreenshotEntity(
                    mediaStoreId = ++mediaCounter,
                    uri = "content://media/external/images/media/x",
                    filename = "one.png",
                    relativePath = "Pictures/Screenshots",
                    dateAdded = added,
                    dateModified = added,
                    fileSize = 100_000,
                    width = 1080,
                    height = 2400,
                    mimeType = "image/png",
                    ocrText = "Pixel 9a unique listing xyzzy",
                    contentHash = "same-hash",
                    duplicateOfId = null,
                    status = "COMPLETED",
                    processingError = null,
                    createdAt = added * 1000,
                    updatedAt = added * 1000,
                ),
            ),
        )[0]
        val second = dao.insertIgnoring(
            listOf(
                ScreenshotEntity(
                    mediaStoreId = ++mediaCounter,
                    uri = "content://media/external/images/media/y",
                    filename = "two.png",
                    relativePath = "Pictures/Screenshots",
                    dateAdded = added,
                    dateModified = added,
                    fileSize = 100_000,
                    width = 1080,
                    height = 2400,
                    mimeType = "image/png",
                    ocrText = "Pixel 9a unique listing xyzzy",
                    contentHash = "same-hash",
                    duplicateOfId = first,
                    status = "COMPLETED",
                    processingError = null,
                    createdAt = added * 1000,
                    updatedAt = added * 1000,
                ),
            ),
        )[0]
        semantic.indexScreenshot(ScreenshotDocument(first, "Pixel 9a unique listing xyzzy", "one.png"))
        semantic.indexScreenshot(ScreenshotDocument(second, "Pixel 9a unique listing xyzzy", "two.png"))

        val response = resultsFor("xyzzy")

        assertEquals(1, response.results.size)
        assertEquals(2, response.results.single().collapsedCount)
    }

    /**
     * Regression guard for a runaway rebuild found on-device: screenshots with
     * no OCR text can never be embedded, so they must never count as stale —
     * otherwise a rebuild worker loops on them forever, draining the battery
     * for zero progress.
     */
    @Test
    fun blankOcrScreenshotsAreNeverStale() = runBlocking {
        val dao = database.screenshotDao()
        val added = nowSeconds()
        // Raw rows, deliberately NOT indexed: both start out stale-eligible.
        val ids = dao.insertIgnoring(
            listOf(
                ScreenshotEntity(
                    mediaStoreId = ++mediaCounter,
                    uri = "content://media/external/images/media/x",
                    filename = "blank.png",
                    relativePath = "Pictures/Screenshots",
                    dateAdded = added,
                    dateModified = added,
                    fileSize = 100_000,
                    width = 1080,
                    height = 2400,
                    mimeType = "image/png",
                    ocrText = "",
                    contentHash = "hash-blank",
                    duplicateOfId = null,
                    status = "COMPLETED",
                    processingError = null,
                    createdAt = added * 1000,
                    updatedAt = added * 1000,
                ),
                ScreenshotEntity(
                    mediaStoreId = ++mediaCounter,
                    uri = "content://media/external/images/media/y",
                    filename = "text.png",
                    relativePath = "Pictures/Screenshots",
                    dateAdded = added,
                    dateModified = added,
                    fileSize = 100_000,
                    width = 1080,
                    height = 2400,
                    mimeType = "image/png",
                    ocrText = "Pixel 9a smartphone deal",
                    contentHash = "hash-text",
                    duplicateOfId = null,
                    status = "COMPLETED",
                    processingError = null,
                    createdAt = added * 1000,
                    updatedAt = added * 1000,
                ),
            ),
        )

        val stale = semantic.staleIds(100)

        assertTrue("text screenshot missing from $stale", ids[1] in stale)
        assertTrue("blank screenshot must never be stale", ids[0] !in stale)
    }

    @Test
    fun unmatchableTextIsNeverAnsweredWithABrowse() = runBlocking {
        seedBenchmark()
        // No fixture contains "xyzzyqq": the lexical ladder is empty at every
        // rung. The old code answered with the whole library disguised as
        // FILTERS_ONLY results; the honest answer is no results.
        val lexicalOnly = LocalSearchEngine(
            dao = database.screenshotDao(),
            history = NoHistory,
            semanticRepository = null,
        )
        val response = lexicalOnly.search(SearchRequest("xyzzyqq"))

        assertTrue(response.results.isEmpty())
    }

    @Test
    fun semanticFallbackAnswersWhenLexicalFindsNothing() = runBlocking {
        val ids = seedBenchmark()
        val target = ids.getValue("C")
        val engineWithStub = LocalSearchEngine(
            dao = database.screenshotDao(),
            history = NoHistory,
            semanticRepository = StubSemanticRepository(target),
            semanticSettings = EnabledSettings,
        )
        val response = engineWithStub.search(SearchRequest("xyzzyqq"))

        assertEquals(1, response.results.size)
        assertEquals(target, response.results.single().screenshot.id)
        assertTrue(response.semanticUsed)
        assertTrue(
            response.results.single().matches.any { it.kind == MatchKind.SEMANTIC },
        )
    }

    @Test
    fun deletingAScreenshotRemovesItsSemanticRows() = runBlocking {        val ids = seedBenchmark()
        val id = ids.getValue("A")
        assertTrue((database.semanticDao().embeddingFor(id)) != null)
        assertTrue(database.semanticDao().categoriesFor(id).isNotEmpty())

        database.screenshotDao().deleteById(id)

        assertTrue(database.semanticDao().embeddingFor(id) == null)
        assertTrue(database.semanticDao().categoriesFor(id).isEmpty())
    }

    private object NoHistory : SearchHistoryRepository {
        override suspend fun isEnabled(): Boolean = false
        override fun observeRecent(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun record(query: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun count(): Int = 0
    }

    private object EnabledSettings : SettingsRepository {
        override fun observeTheme() = flowOf(ThemeMode.SYSTEM)
        override suspend fun setTheme(mode: ThemeMode) = Unit
        override fun observeScope() = flowOf(IndexingScope.SCREENSHOTS_ONLY)
        override suspend fun setScope(scope: IndexingScope) = Unit
        override fun observeOnboardingDone() = flowOf(true)
        override suspend fun setOnboardingDone(done: Boolean) = Unit
        override suspend fun isSearchHistoryEnabled(): Boolean = false
        override suspend fun setSearchHistoryEnabled(enabled: Boolean) = Unit
        override suspend fun isSemanticSearchEnabled(): Boolean = true
        override suspend fun setSemanticSearchEnabled(enabled: Boolean) = Unit
        override suspend fun processingMode() = ProcessingMode.AUTOMATIC
        override suspend fun setProcessingMode(mode: ProcessingMode) = Unit
        override suspend fun isAutomationEnabled(): Boolean = true
        override suspend fun setAutomationEnabled(enabled: Boolean) = Unit
        override suspend fun areContextActionsEnabled(): Boolean = true
        override suspend fun setContextActionsEnabled(enabled: Boolean) = Unit
        override suspend fun isExpenseExtractionEnabled(): Boolean = true
        override suspend fun setExpenseExtractionEnabled(enabled: Boolean) = Unit
        // Phase 8
        override suspend fun isAppLockEnabled(): Boolean = false
        override suspend fun setAppLockEnabled(enabled: Boolean) = Unit
        override suspend fun isSensitiveContentProtectionEnabled(): Boolean = true
        override suspend fun setSensitiveContentProtectionEnabled(enabled: Boolean) = Unit
        override suspend fun isSecureWindowEnabled(): Boolean = true
        override suspend fun setSecureWindowEnabled(enabled: Boolean) = Unit
        override suspend fun isNotificationPrivacyEnabled(): Boolean = true
        override suspend fun setNotificationPrivacyEnabled(enabled: Boolean) = Unit
        override suspend fun isClipboardProtectionEnabled(): Boolean = true
        override suspend fun setClipboardProtectionEnabled(enabled: Boolean) = Unit
        override suspend fun isAssistantHistoryEnabled(): Boolean = true
        override suspend fun setAssistantHistoryEnabled(enabled: Boolean) = Unit
        override suspend fun isAnalyticsEnabled(): Boolean = false
        override suspend fun setAnalyticsEnabled(enabled: Boolean) = Unit
        override suspend fun isCrashReportingEnabled(): Boolean = false
        override suspend fun setCrashReportingEnabled(enabled: Boolean) = Unit
    }

    private object DisabledSettings : SettingsRepository by EnabledSettings {
        override suspend fun isSemanticSearchEnabled(): Boolean = false
    }

    /**
     * Returns one canned match regardless of the query, so the fallback branch
     * is exercised deterministically. Everything else is inert.
     */
    private class StubSemanticRepository(
        private val matchId: Long,
    ) : SemanticRepository {
        override val isAvailable: Boolean = true
        override val modelInfo = com.ssintelligence.app.semantic.SemanticModelInfo(
            name = "stub",
            version = "1",
            dimension = 8,
            sizeDescription = "test stub",
        )

        override suspend fun indexScreenshot(
            document: com.ssintelligence.app.semantic.ScreenshotDocument,
        ) = Unit

        override suspend fun removeScreenshot(screenshotId: Long) = Unit

        override suspend fun embeddingFor(screenshotId: Long) = null

        override suspend fun categoriesFor(screenshotId: Long) = emptyList<com.ssintelligence.app.semantic.CategoryAssignment>()

        override suspend fun setUserCategory(
            screenshotId: Long,
            category: com.ssintelligence.app.semantic.ScreenshotCategory,
        ) = Unit

        override suspend fun clearUserCategory(screenshotId: Long) = Unit

        override suspend fun findSimilar(
            embedding: com.ssintelligence.app.semantic.TextEmbedding,
            excludeId: Long,
            limit: Int,
        ) = emptyList<com.ssintelligence.app.semantic.SemanticMatch>()

        override suspend fun findSimilarTo(
            document: com.ssintelligence.app.semantic.ScreenshotDocument,
            limit: Int,
        ) = emptyList<com.ssintelligence.app.semantic.SemanticMatch>()

        override suspend fun searchSimilar(
            queryText: String,
            expansionTerms: List<String>,
            limit: Int,
        ) = listOf(com.ssintelligence.app.semantic.SemanticMatch(matchId, 0.9))

        override suspend fun staleIds(limit: Int) = emptyList<Long>()

        override suspend fun embeddedCount(): Int = 0

        override suspend fun clearSemanticIndex() = Unit

        override suspend fun summarize(
            document: com.ssintelligence.app.semantic.ScreenshotDocument,
            phrases: List<String>,
        ) = ""
    }
}
