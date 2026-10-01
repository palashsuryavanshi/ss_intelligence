package com.ssintelligence.app.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssintelligence.app.data.database.ExtractedOtpEntity
import com.ssintelligence.app.data.database.ExtractedPhoneEntity
import com.ssintelligence.app.data.database.ExtractedPriceEntity
import com.ssintelligence.app.data.database.ExtractedUrlEntity
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
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
import kotlin.system.measureTimeMillis

/**
 * The whole pipeline against a real database: parse → SQL retrieval → ranking
 * (§42, §45).
 *
 * Instrumented because it needs SQLite to answer the FTS and structured
 * queries, and because the performance budgets in §45 are only meaningful
 * against a real engine.
 *
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LocalSearchEngineInstrumentedTest {

    private lateinit var database: SsIntelligenceDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SsIntelligenceDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun engine(candidateLimit: Int = LocalSearchEngine.CANDIDATE_LIMIT) =
        LocalSearchEngine(
            dao = database.screenshotDao(),
            history = NoHistory,
            candidateLimit = candidateLimit,
        )

    // ------------------------------------------------------------ fixtures

    private fun nowSeconds(): Long =
        LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().epochSecond

    private var mediaCounter = 0L

    private suspend fun seedScreenshot(
        ocr: String,
        filename: String,
        daysAgo: Long = 1,
        price: Pair<Double, String>? = null,
        host: String? = null,
        phone: String? = null,
        otp: String? = null,
        duplicateOf: Long? = null,
        /**
         * Absolute epoch seconds, overriding [daysAgo]. Calendar-bounded tests
         * need it: "3 days ago" lands in the previous month on the 1st, 2nd or
         * 3rd of a month, which would make those tests fail by date alone.
         */
        addedAt: Long? = null,
    ): Long {
        val dao = database.screenshotDao()
        val added = addedAt ?: (nowSeconds() - daysAgo * 86_400)
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
                    duplicateOfId = duplicateOf,
                    status = "COMPLETED",
                    processingError = null,
                    createdAt = added * 1000,
                    updatedAt = added * 1000,
                ),
            ),
        )[0]
        price?.let { dao.insertPrices(listOf(ExtractedPriceEntity(0, id, "x", it.second, it.first))) }
        host?.let { dao.insertUrls(listOf(ExtractedUrlEntity(0, id, "https://$host/deal", host))) }
        phone?.let { dao.insertPhones(listOf(ExtractedPhoneEntity(0, id, it, it, "IN"))) }
        otp?.let { dao.insertOtps(listOf(ExtractedOtpEntity(0, id, it))) }
        return id
    }

    // ------------------------------------------------------------ basic

    @Test
    fun keywordSearchFindsRowsContainingTheWord() = runBlocking {
        seedScreenshot("Google Pixel 9a 5G", "Screenshot_1.png")
        seedScreenshot("Flight ticket booking", "Screenshot_2.png")

        val results = engine().search("Pixel")

        assertEquals(1, results.size)
        assertTrue(results.single().matches.any { it.kind == MatchKind.PHRASE || it.kind == MatchKind.TERM })
    }

    @Test
    fun multiWordSearchIsNotSilentlyEmpty() = runBlocking {
        // The Phase 1 regression: FTS4 rejects an explicit AND, so a multi-term
        // query used to match nothing at all without any error.
        seedScreenshot("Google Pixel 9a 5G 128GB", "Screenshot_1.png")
        seedScreenshot("Flight ticket to Delhi", "Screenshot_2.png")

        val results = engine().search("Pixel 9a")

        assertEquals(1, results.size)
    }

    @Test
    fun phraseSearchRanksTheExactMatchFirst() = runBlocking {
        val exact = seedScreenshot("Google Pixel 9a 5G 128GB", "Screenshot_exact.png", daysAgo = 90)
        seedScreenshot(
            "Pixel phone with Android 9a update available",
            "Screenshot_other.png",
            daysAgo = 1,
        )

        val results = engine().search("Pixel 9a")

        assertEquals(exact, results.first().screenshot.id)
    }

    // ------------------------------------------------------------ prices

    @Test
    fun priceQueryFiltersByExtractedAmount() = runBlocking {
        seedScreenshot("Pixel 9a", "a.png", price = 39999.0 to Currency.INR)
        seedScreenshot("OnePlus 13", "b.png", price = 64999.0 to Currency.INR)

        val results = engine().search("₹39,999")

        assertEquals(1, results.size)
        assertEquals("a.png", results.single().screenshot.filename)
    }

    @Test
    fun priceOperatorBecomesAnUpperBound() = runBlocking {
        seedScreenshot("Budget phones under forty thousand", "cheap.png", price = 15000.0 to Currency.INR)
        seedScreenshot("Premium phones over eighty thousand", "pricey.png", price = 80000.0 to Currency.INR)

        val results = engine().search("phones below ₹40,000")

        assertEquals(1, results.size)
        assertEquals("cheap.png", results.single().screenshot.filename)
    }

    @Test
    fun currencyIsNotConverted() = runBlocking {
        seedScreenshot("Laptop", "usd.png", price = 1299.0 to Currency.USD)
        seedScreenshot("Laptop", "inr.png", price = 1299.0 to Currency.INR)

        val results = engine().search("laptop ₹1,299")

        assertEquals(1, results.size)
        assertEquals("inr.png", results.single().screenshot.filename)
    }

    @Test
    fun exactPriceFallsBackToTheToleranceBand() = runBlocking {
        seedScreenshot("Pixel 9a", "close.png", price = 40099.0 to Currency.INR)
        seedScreenshot("Pixel 9a", "far.png", price = 60000.0 to Currency.INR)

        val response = engine().search(SearchRequest("Pixel 9a for ₹39,999"))

        assertEquals(1, response.results.size)
        assertEquals("close.png", response.results.single().screenshot.filename)
        assertEquals(RelaxationLevel.PRICE_APPROXIMATE, response.relaxation)
        assertTrue(response.relaxation.userMessage!!.isNotBlank())
    }

    // ------------------------------------------------------- combined

    @Test
    fun combinedQueryCombinesTextAndPrice() = runBlocking {
        val wanted = seedScreenshot(
            "Google Pixel 9a 5G 128GB",
            "wanted.png",
            price = 39999.0 to Currency.INR,
        )
        seedScreenshot(
            "Google Pixel 9a 5G 128GB",
            "wrong_price.png",
            price = 42999.0 to Currency.INR,
        )
        seedScreenshot("OnePlus 13", "wrong_text.png", price = 39999.0 to Currency.INR)

        val results = engine().search("Find the screenshot where I saw Pixel 9a for ₹39,999")

        assertEquals(wanted, results.first().screenshot.id)
    }

    @Test
    fun relaxationDropsThePriceWhenTextAloneMatches() = runBlocking {
        seedScreenshot("Pixel 9a 5G", "no_price.png")
        seedScreenshot("Pixel 9a ₹45,000", "wrong_price.png", price = 45000.0 to Currency.INR)

        val response = engine().search(SearchRequest("Pixel 9a for ₹39,999"))

        assertFalse(response.results.isEmpty())
        assertEquals(RelaxationLevel.TEXT_ONLY, response.relaxation)
    }

    // ------------------------------------------------------------ dates

    @Test
    fun dateFilterNarrowsToTheRequestedWindow() = runBlocking {
        seedScreenshot("Fresh purchase", "today.png", daysAgo = 0)
        seedScreenshot("Old purchase", "last_year.png", daysAgo = 400)

        val results = engine().search("screenshots from today")

        assertEquals(1, results.size)
        assertEquals("today.png", results.single().screenshot.filename)
    }

    @Test
    fun monthFilterUsesLocalCalendarBoundaries() = runBlocking {
        val now = LocalDateTime.now().atZone(ZoneId.systemDefault())
        // Seeded at the 1st of the current month rather than "3 days ago": the
        // test must exercise the month boundary, not the current day-of-month.
        val firstOfMonth = now.withDayOfMonth(1).toLocalDate().atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().epochSecond
        val inMonth = seedScreenshot("Receipt", "in.png", addedAt = firstOfMonth)
        seedScreenshot("Receipt", "ancient.png", daysAgo = 400)

        val results = engine().search("screenshots from ${now.month.name.lowercase().replaceFirstChar { it.uppercase() }}")

        assertEquals(1, results.size)
        assertEquals(inMonth, results.single().screenshot.id)
    }

    // ------------------------------------------------------------- URLs

    @Test
    fun domainSearchMatchesTheHostAndItsSubdomains() = runBlocking {
        seedScreenshot("Deal of the day", "smile.png", host = "smile.amazon.in")
        seedScreenshot("Different shop", "other.png", host = "flipkart.com")

        val results = engine().search("screenshots from amazon.in")

        assertEquals(1, results.size)
        assertEquals("smile.png", results.single().screenshot.filename)
    }

    @Test
    fun aLookalikeHostDoesNotMatch() = runBlocking {
        seedScreenshot("Deal", "fake.png", host = "notamazon.in")

        val results = engine().search("screenshots from amazon.in")

        assertTrue(results.isEmpty())
    }

    // ---------------------------------------------------- phones and codes

    @Test
    fun phoneSearchNormalizesBothSides() = runBlocking {
        val target = seedScreenshot("Call for details", "call.png", phone = "+919876543210")
        seedScreenshot("Call for details", "other.png", phone = "+919876543211")

        val results = engine().search("find screenshot containing 9876543210")

        assertEquals(1, results.size)
        assertEquals(target, results.single().screenshot.id)
    }

    @Test
    fun codeSearchMatchesWithoutRevealingTheValue() = runBlocking {
        seedScreenshot("Your verification", "otp.png", otp = "483921")

        val results = engine().search("find the screenshot with OTP 483921")

        assertEquals(1, results.size)
        val reason = results.single().matches.single { it.kind == MatchKind.OTP }
        assertEquals("One-time code", reason.label)
    }

    @Test
    fun duplicateSearchListsOnlyDuplicates() = runBlocking {
        val original = seedScreenshot("Same", "orig.png", daysAgo = 3)
        seedScreenshot("Same", "dupe.png", daysAgo = 2, duplicateOf = original)
        seedScreenshot("Unique", "unique.png", daysAgo = 1)

        val results = engine().search("show duplicate screenshots")

        assertEquals(1, results.size)
        assertEquals("dupe.png", results.single().screenshot.filename)
    }

    /**
     * Regression guard for an FTS4 silent-rejection bug in the same family as
     * Phase 1's explicit-AND discovery: this configuration matches zero rows
     * when a prefix query (`"a"*`) is combined with OR, with no error. The
     * builder therefore emits exact-token OR, and this test pins that the
     * emitted shape actually matches.
     */
    @Test
    fun orMatchExpressionFindsEitherTerm() = runBlocking {
        val dao = database.screenshotDao()
        val added = nowSeconds()
        dao.insertIgnoring(
            listOf(
                ScreenshotEntity(
                    mediaStoreId = ++mediaCounter,
                    uri = "content://media/external/images/media/x",
                    filename = "or_probe.png",
                    relativePath = "Pictures/Screenshots",
                    dateAdded = added,
                    dateModified = added,
                    fileSize = 100_000,
                    width = 1080,
                    height = 2400,
                    mimeType = "image/png",
                    ocrText = "booking confirmation number",
                    contentHash = "hash-or-probe",
                    duplicateOfId = null,
                    status = "COMPLETED",
                    processingError = null,
                    createdAt = added * 1000,
                    updatedAt = added * 1000,
                ),
            ),
        )
        val expression = FtsQueryBuilder.buildFromTerms(
            listOf("travel", "booking"),
            FtsQueryBuilder.Conjunction.OR,
        )!!
        assertEquals(1, dao.searchIdsByText(expression, 10).size)
    }

    // -------------------------------------------------------- filters

    @Test
    fun manualFiltersNarrowTheResults() = runBlocking {
        seedScreenshot("Phone", "cheap.png", price = 10000.0 to Currency.INR)
        seedScreenshot("Phone", "dear.png", price = 90000.0 to Currency.INR)

        val response = engine().search(
            SearchRequest(
                query = "phone",
                manualFilters = ManualFilters(minPrice = 50000.0),
            ),
        )

        assertEquals(1, response.results.size)
        assertEquals("dear.png", response.results.single().screenshot.filename)
    }

    @Test
    fun contentTypeChipSelectsRowsWithThatContent() = runBlocking {
        seedScreenshot("With a link", "link.png", host = "example.com")
        seedScreenshot("No link", "plain.png")

        val response = engine().search(SearchRequest(query = "", contentTypes = setOf(ContentType.URLS)))

        assertEquals(1, response.results.size)
        assertEquals("link.png", response.results.single().screenshot.filename)
    }

    @Test
    fun sortModeNewestOverridesRelevance() = runBlocking {
        seedScreenshot("Pixel", "old.png", daysAgo = 40)
        seedScreenshot("Pixel", "new.png", daysAgo = 1)

        val newest = engine().search(SearchRequest("pixel", sortMode = SortMode.NEWEST))
        assertEquals("new.png", newest.results.first().screenshot.filename)

        val oldest = engine().search(SearchRequest("pixel", sortMode = SortMode.OLDEST))
        assertEquals("old.png", oldest.results.first().screenshot.filename)
    }

    @Test
    fun anEmptyQueryWithNoFiltersBrowsesNewestFirst() = runBlocking {
        seedScreenshot("older", "older.png", daysAgo = 5)
        seedScreenshot("newer", "newer.png", daysAgo = 1)

        val response = engine().search(SearchRequest(""))

        assertEquals(2, response.results.size)
        assertEquals("newer.png", response.results.first().screenshot.filename)
    }

    // --------------------------------------------------------- deletion

    @Test
    fun aDeletedScreenshotCannotBeFound() = runBlocking {
        val id = seedScreenshot("Pixel 9a ₹39,999", "gone.png", price = 39999.0 to Currency.INR)
        assertEquals(1, engine().search("Pixel 9a").size)

        database.screenshotDao().deleteById(id)

        assertTrue(engine().search("Pixel 9a").isEmpty())
        assertTrue(engine().search("₹39,999").isEmpty())
    }

    // ------------------------------------------------------ performance

    @Test
    fun searchStaysResponsiveOverTenThousandScreenshots() = runBlocking {
        val dao = database.screenshotDao()
        val base = nowSeconds()
        val rows = (0 until 10_000).map { index ->
            val added = base - index * 3_600L
            ScreenshotEntity(
                mediaStoreId = index.toLong(),
                uri = "content://media/external/images/media/$index",
                filename = "Screenshot_2026-09-%02d-%02d.png".format(index % 28 + 1, index % 24),
                relativePath = "Pictures/Screenshots",
                dateAdded = added,
                dateModified = added,
                fileSize = 100_000,
                width = 1080,
                height = 2400,
                mimeType = "image/png",
                ocrText = OCR_SAMPLES[index % OCR_SAMPLES.size].replace("{n}", index.toString()),
                contentHash = "hash-$index",
                duplicateOfId = null,
                status = "COMPLETED",
                processingError = null,
                createdAt = added * 1000,
                updatedAt = added * 1000,
            )
        }
        dao.insertIgnoring(rows)
        val ids = dao.getByIds(rows.indices.map { 1L + it })

        // Structured metadata for a slice of the library, so the price and URL
        // filters have something to match against.
        dao.insertPrices(
            ids.take(2_000).map {
                ExtractedPriceEntity(0, it.id, "x", Currency.INR, 10000.0 + it.id % 60_000)
            },
        )
        dao.insertUrls(
            ids.take(2_000).map {
                ExtractedUrlEntity(0, it.id, "https://amazon.in/p/${it.id}", "amazon.in")
            },
        )

        val searchEngine = engine()
        val budgets = mapOf(
            "simple keyword" to "Pixel",
            "phrase" to "Pixel 9a",
            "price" to "₹39,999",
            "price operator" to "phones below ₹40,000",
            "combined" to "Pixel 9a for ₹39,999",
            "date" to "screenshots from last week",
            "url" to "screenshots from amazon.in",
        )

        for ((label, query) in budgets) {
            val elapsed = measureTimeMillis { searchEngine.search(query) }
            println("$label: ${elapsed}ms over 10k rows")
            assertTrue("$label took ${elapsed}ms", elapsed < BUDGET_MS)
        }
    }

    @Test
    fun aSmallCandidateWindowStillFindsTheRightRow() = runBlocking {
        val target = seedScreenshot("Google Pixel 9a 5G 128GB", "target.png", daysAgo = 500)
        repeat(30) { index ->
            seedScreenshot("Pixel phone update $index", "noise$index.png", daysAgo = index.toLong())
        }

        // A tight window is what the engine uses; the union with the phrase
        // query is what keeps the exact match inside it.
        val results = engine(candidateLimit = 40).search("Pixel 9a")

        assertTrue(results.any { it.screenshot.id == target })
    }

    private object NoHistory : SearchHistoryRepository {
        override suspend fun isEnabled(): Boolean = false
        override fun observeRecent(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun record(query: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun count(): Int = 0
    }

    private companion object {
        /** Generous enough not to be flaky on a slow emulator, tight enough to matter. */
        const val BUDGET_MS = 2_000L

        val OCR_SAMPLES = listOf(
            "Store Home Offers Google Pixel 9a 5G 128GB ₹39,999 Add to cart {n}",
            "Flight ticket Delhi to Mumbai PNR 7QK2LP fare ₹6,500 {n}",
            "Your verification code is {n} valid for 10 minutes",
            "Amazon Great Indian Festival up to 70% off on headphones {n}",
            "Order confirmed. Item: USB-C cable. Total ₹299 {n}",
            "UPI payment of ₹1,250 to 9876543210 successful {n}",
            "GitHub pull request #{n} merged into main",
            "Google Maps directions to home, 12 min drive",
        )
    }
}
