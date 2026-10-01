package com.ssintelligence.app.visual

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssintelligence.app.data.database.ExtractedPriceEntity
import com.ssintelligence.app.data.database.ExtractedUrlEntity
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.data.database.ScreenshotVisualEntity
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.data.repository.SemanticRepositoryImpl
import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.graph.GraphEntityType
import com.ssintelligence.app.graph.GraphRepositoryImpl
import com.ssintelligence.app.search.LocalSearchEngine
import com.ssintelligence.app.search.SearchRequest
import com.ssintelligence.app.semantic.HashedNgramEmbeddingProvider
import com.ssintelligence.app.semantic.ScreenshotDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Visual similarity, palette search, the knowledge graph, collections,
 * timeline, events, sequences and comparison against a real database
 * (§57–§59).
 *
 * Visual rows are inserted directly: the hash math is unit-tested on pixel
 * arrays, and what matters here is ranking, grouping and graph integrity over
 * real SQLite. Bitmap decoding itself is covered by the analyzer test on
 * synthetic pixels.
 */
@RunWith(AndroidJUnit4::class)
class VisualGraphInstrumentedTest {

    private lateinit var database: SsIntelligenceDatabase
    private var mediaCounter = 0L

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

    private fun engine() = LocalSearchEngine(
        dao = database.screenshotDao(),
        history = NoHistory,
        semanticRepository = SemanticRepositoryImpl(
            database = database,
            dao = database.screenshotDao(),
            semanticDao = database.semanticDao(),
            provider = HashedNgramEmbeddingProvider(),
        ),
        visualDao = database.visualDao(),
        graphRepository = GraphRepositoryImpl(
            database.graphDao(),
            database.screenshotDao(),
            database.semanticDao(),
        ),
    )

    private fun graph() = GraphRepositoryImpl(
        database.graphDao(),
        database.screenshotDao(),
        database.semanticDao(),
    )

    private fun nowSeconds(): Long =
        LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().epochSecond

    private suspend fun seed(
        ocr: String,
        filename: String,
        width: Int = 1080,
        height: Int = 2400,
        price: Pair<Double, String>? = null,
        host: String? = null,
        daysAgo: Long = 0,
    ): Long {
        val dao = database.screenshotDao()
        val added = nowSeconds() - daysAgo * 86_400
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
                    width = width,
                    height = height,
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
        return id
    }

    private suspend fun seedVisual(
        id: Long,
        dhash: Long,
        colors: String = "white,gray",
        type: String = "APP_UI",
    ) {
        database.visualDao().upsertVisual(
            ScreenshotVisualEntity(
                screenshotId = id,
                dhash = dhash,
                colors = colors,
                brightness = 200.0,
                isDark = false,
                textCoverage = 0.3,
                shotType = type,
                layout = "NONE",
                modelVersion = "visual-v1",
                createdAt = 0,
            ),
        )
    }

    private suspend fun fileGraph(
        id: Long,
        ocr: String,
        phrases: List<String>,
        hosts: List<String> = emptyList(),
        prices: List<ExtractedPrice> = emptyList(),
    ) {
        graph().fileScreenshot(
            id,
            ScreenshotDocument(
                screenshotId = id,
                ocrText = ocr,
                filename = "s.png",
                hosts = hosts,
                prices = prices,
            ),
            phrases,
            emptyList(),
        )
    }

    // ------------------------------------------------------------ visual

    @Test
    fun visuallySimilarRanksCloseHashesFirst() = runBlocking {
        val target = seed("Pixel 9a listing", "t.png")
        val near = seed("Pixel 9a listing updated", "n.png")
        val far = seed("Flight ticket booking", "f.png")
        // Same layout with two bits flipped vs a distant layout (56 bits).
        // The distant row is correctly excluded: 56 differing bits share
        // nothing visual, and listing it as "similar" would be a lie.
        seedVisual(target, 0b11110000L)
        seedVisual(near, 0b11110011L)
        seedVisual(far, 0b00001111L.inv())

        val similar = database.run {
            com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl(
                ApplicationProvider.getApplicationContext(),
                this,
                screenshotDao(),
            ).visuallySimilar(target)
        }

        assertEquals(listOf(near), similar.map { it.screenshot.id })
        assertEquals(2, similar.first().distanceBits)
    }

    @Test
    fun nearDuplicatesGroupButAreNotDuplicates() = runBlocking {
        val dao = database.screenshotDao()
        val a = seed("Pixel 9a ₹39,999", "a.png")
        val b = seed("Pixel 9a ₹40,999", "b.png")
        val c = seed("Flight ticket", "c.png")
        seedVisual(a, 0xFF00L)
        seedVisual(b, 0xFF03L)
        seedVisual(c, 0xFF00L.inv())

        val repo = com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            database,
            dao,
        )
        val groups = repo.nearDuplicateGroups()

        assertEquals(1, groups.size)
        assertEquals(setOf(a, b), groups.single().memberIds.toSet())
        assertTrue(groups.single().maxDistanceBits <= 8)
    }

    @Test
    fun colorQueryMatchesPalettes() = runBlocking {
        val blue = seed("Settings page", "blue.png")
        val white = seed("Settings page", "white.png")
        seedVisual(blue, 1L, colors = "blue,white,gray")
        seedVisual(white, 2L, colors = "white,gray,black")

        val response = engine().search(SearchRequest("blue screenshots"))

        assertEquals(listOf(blue), response.results.map { it.screenshot.id })
        assertTrue(
            response.results.single().matches.any {
                it.kind == com.ssintelligence.app.search.MatchKind.VISUAL
            },
        )
    }

    @Test
    fun longScreenshotQueryMatchesTallImages() = runBlocking {
        val tall = seed("Long chat log", "tall.png", width = 1080, height = 7000)
        seed("Normal shot", "normal.png", width = 1080, height = 2400)

        val response = engine().search(SearchRequest("long screenshots"))

        assertEquals(listOf(tall), response.results.map { it.screenshot.id })
    }

    @Test
    fun searchByImageRanksLookalikesFirst() = runBlocking {
        val target = seed("Pixel 9a Amazon", "t.png")
        val lookalike = seed("Totally different words here xyzzy", "l.png")
        val other = seed("Another different BBM", "o.png")
        // Lookalike shares pixels but no words with the target.
        seedVisual(target, 0b10101010L)
        seedVisual(lookalike, 0b10101011L)
        seedVisual(other, 0b01010101L.inv())

        val response = engine().search(SearchRequest("", visualQueryId = target))

        assertEquals(lookalike, response.results.first().screenshot.id)
        assertTrue(
            response.results.first().matches.any {
                it.kind == com.ssintelligence.app.search.MatchKind.VISUAL
            },
        )
    }

    /**
     * A pinned image with an empty text box is a search on its own.
     *
     * Found on the device: the pin rendered but the engine returned nothing,
     * because a blank query has no term to retrieve against and every rung of
     * the ladder had no constraint to work from.
     */
    @Test
    fun visualOnlySearchRanksWithoutAnyText() = runBlocking {
        val target = seed("Some words", "t.png")
        val lookalike = seed("Unrelated words entirely", "l.png")
        seedVisual(target, 0x0F0F0F0FL)
        seedVisual(lookalike, 0x0F0F0F0CL)
        seed("Distant", "d.png").also { seedVisual(it, 0xF0F0F0F0L) }

        val response = engine().search(SearchRequest("", visualQueryId = target))

        assertEquals(listOf(lookalike), response.results.map { it.screenshot.id })
        // The query image is trivially identical to itself and steps aside.
        assertTrue(response.results.none { it.screenshot.id == target })
        // A visual match is not a text match: no snippet, no text reason.
        assertTrue(response.results.single().snippet == null)
        assertTrue(
            response.results.single().matches.all {
                it.kind == com.ssintelligence.app.search.MatchKind.VISUAL
            },
        )
    }

    @Test
    fun visualOnlySearchAnswersNothingRatherThanBrowsing() = runBlocking {
        val target = seed("A", "t.png")
        val distant = seed("B", "d.png")
        seedVisual(target, 0L)
        // 32 of 64 bits differ: far past the similarity threshold, so the row
        // is a different screenshot rather than a weak match.
        seedVisual(distant, 0x0F0F0F0F0F0F0F0FL)
        seed("C", "c.png").also { seedVisual(it, 0x00FF00FF00FF00FFL) }

        val response = engine().search(SearchRequest("", visualQueryId = target))

        // Nothing is similar, and the answer is no results — never the whole
        // library listed under a pinned image.
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun textAndImageCombineRatherThanReplace() = runBlocking {
        val target = seed("Pixel 9a", "t.png")
        val both = seed("Pixel 9a deal", "b.png")
        val lookalikeOnly = seed("Unrelated text", "l.png")
        seedVisual(target, 0xAAAAAAAA)
        seedVisual(both, 0xAAAAAAAAL)
        seedVisual(lookalikeOnly, 0xAAAAAAAAL)

        val response = engine().search(SearchRequest("Pixel 9a", visualQueryId = target))

        // The row matching the words and the pixels outranks the one that only
        // shares pixels.
        assertEquals(both, response.results.first().screenshot.id)
    }

    // -------------------------------------------------------------- graph

    @Test
    fun entityPageCollectsMembersPricesAndWebsites() = runBlocking {
        val a = seed("Pixel 9a deal", "a.png", price = 39999.0 to "INR", host = "amazon.in")
        val b = seed("Pixel 9a offer", "b.png", price = 41999.0 to "INR", host = "flipkart.com")
        val c = seed("Flight ticket", "c.png")
        fileGraph(
            a, "Pixel 9a deal", listOf("Pixel 9a"), listOf("amazon.in"),
            listOf(ExtractedPrice(0, a, "x", "INR", 39999.0)),
        )
        fileGraph(
            b, "Pixel 9a offer", listOf("Pixel 9a"), listOf("flipkart.com"),
            listOf(ExtractedPrice(0, b, "x", "INR", 41999.0)),
        )
        fileGraph(c, "Flight ticket", listOf("flight ticket"))

        val entity = graph().resolveEntity(GraphEntityType.PRODUCT, "pixel 9a")!!
        val page = graph().entityPage(entity.id)!!

        assertEquals(setOf(a, b), page.screenshotIds.toSet())
        assertEquals(2, page.pricesSeen.size)
        // Prices in screenshot-date order, earliest first.
        assertTrue(page.pricesSeen[0].seenAtSeconds <= page.pricesSeen[1].seenAtSeconds)
        assertEquals(setOf("amazon.in", "flipkart.com"), page.websites.toSet())
    }

    @Test
    fun deletingAScreenshotRemovesItsEdgesButKeepsSharedEntities() = runBlocking {
        val a = seed("Pixel 9a deal", "a.png")
        val b = seed("Pixel 9a offer", "b.png")
        fileGraph(a, "Pixel 9a deal", listOf("Pixel 9a"))
        fileGraph(b, "Pixel 9a offer", listOf("Pixel 9a"))
        val entityId = graph().resolveEntity(GraphEntityType.PRODUCT, "pixel 9a")!!.id
        assertEquals(2, graph().screenshotIdsForEntity(entityId).size)

        database.screenshotDao().deleteById(a)

        // One edge gone, the entity and the other edge survive.
        assertEquals(listOf(b), graph().screenshotIdsForEntity(entityId))
        assertTrue(graph().entityPage(entityId) != null)
    }

    @Test
    fun deletingTheLastReferrerRemovesTheEntity() = runBlocking {
        // "Pixel 9a" carries a model number so it survives the product gate, and
        // its normalized form is unambiguous: the letter→digit split fires on
        // "xz9" but not here, so the key is exactly "pixel 9a".
        val a = seed("Pixel 9a listing", "a.png")
        fileGraph(a, "Pixel 9a listing", listOf("Pixel 9a"))
        val entityId = graph().resolveEntity(GraphEntityType.PRODUCT, "pixel 9a")!!.id

        database.screenshotDao().deleteById(a)
        // Re-file nothing: deletion cascades edges, and the orphan sweep runs
        // on the next filing. Filing an unrelated screenshot triggers it.
        val b = seed("Other words", "b.png")
        fileGraph(b, "Other words", listOf("other words"))

        assertTrue(graph().entityPage(entityId) == null)
    }

    // -------------------------------------------------------- collections

    @Test
    fun collectionsStoreReferencesNotImages() = runBlocking {
        val a = seed("Pixel 9a", "a.png")
        val b = seed("Pixel 9 Pro", "b.png")
        val repo = com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            database,
            database.screenshotDao(),
        )
        val id = repo.createCollection("Pixel Research")
        repo.addToCollection(id, a)
        repo.addToCollection(id, b)
        // Adding twice is a no-op: the unique membership index ignores it.
        repo.addToCollection(id, a)

        // Newest first.
        assertEquals(listOf(b, a), repo.collectionMembers(id).map { it.id })
        val collections = repo.collections()
        assertEquals(1, collections.size)
        assertEquals(2, collections.single().memberCount)

        repo.removeFromCollection(id, a)
        assertEquals(listOf(b), repo.collectionMembers(id).map { it.id })

        // Deleting a screenshot removes it from collections via cascade.
        database.screenshotDao().deleteById(b)
        assertTrue(repo.collectionMembers(id).isEmpty())

        repo.deleteCollection(id)
        assertTrue(repo.collections().isEmpty())
    }

    // ---------------------------------------------------------- organize

    @Test
    fun timelineGroupsByDayWithCategories() = runBlocking {
        val repo = com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            database,
            database.screenshotDao(),
        )
        seed("Morning shot", "a.png", daysAgo = 0)
        seed("Evening shot", "b.png", daysAgo = 0)
        seed("Old shot", "c.png", daysAgo = 5)

        val days = repo.timeline()

        assertEquals(2, days.size)
        assertTrue(days[0].screenshots.size == 2)
        assertTrue(days[1].screenshots.size == 1)
        // Newest day first, days never fabricated.
        assertTrue(days[0].epochDay > days[1].epochDay)
    }

    @Test
    fun eventGroupsShareBookingCodesCloseInTime() = runBlocking {
        val repo = com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            database,
            database.screenshotDao(),
        )
        // The repository under test needs its graph wiring, exactly as the
        // service locator provides it in production.
        repo.graphRepository = GraphRepositoryImpl(
            database.graphDao(),
            database.screenshotDao(),
            database.semanticDao(),
        )
        val a = seed("Flight search PNR 7QK2LP Delhi", "a.png", daysAgo = 3)
        val b = seed("Boarding pass PNR 7QK2LP gate", "b.png", daysAgo = 1)
        seed("Unrelated shot", "c.png", daysAgo = 0)
        val g = graph()
        // The same PNR across screenshots days apart: one trip, filed through
        // the same order-context recognition the pipeline uses.
        for ((id, ocr) in listOf(
            a to "Flight search PNR 7QK2LP Delhi",
            b to "Boarding pass PNR 7QK2LP gate",
        )) {
            g.fileScreenshot(id, ScreenshotDocument(id, ocr, "s.png"), emptyList(), emptyList())
        }

        val events = repo.eventGroups()

        assertEquals(1, events.size)
        assertEquals(setOf(a, b), events.single().memberIds.toSet())
        assertEquals("7QK2LP", events.single().label)
    }

    @Test
    fun sequencesLinkAdjacentOverlappingShots() = runBlocking {
        val repo = com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            database,
            database.screenshotDao(),
        )
        val words = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
        seed("$words one", "a.png", daysAgo = 0)
        seed("$words two", "b.png", daysAgo = 0)
        seed("$words three", "c.png", daysAgo = 0)
        seed("completely different xylophone yogurt", "d.png", daysAgo = 0)

        val sequences = repo.sequences()

        assertEquals(1, sequences.size)
        assertEquals(3, sequences.single().memberIds.size)
    }

    // ------------------------------------------------------------- compare

    @Test
    fun compareUseCaseDiffsTwoScreenshots() = runBlocking {
        val a = seed("Pixel 9a ₹39,999", "a.png", price = 39999.0 to "INR", host = "amazon.in")
        val b = seed("Pixel 9a ₹41,999", "b.png", price = 41999.0 to "INR", host = "flipkart.com")
        val repo = com.ssintelligence.app.data.repository.ScreenshotRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            database,
            database.screenshotDao(),
        )
        val comparison = com.ssintelligence.app.domain.usecase.CompareScreenshotsUseCase(repo)(a, b)!!

        assertTrue(comparison.changes.any { it.contains("₹39999") && it.contains("₹41999") })
        assertTrue(comparison.changes.any { it.contains("amazon.in") && it.contains("flipkart.com") })
    }

    private object NoHistory : SearchHistoryRepository {
        override suspend fun isEnabled(): Boolean = false
        override fun observeRecent(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun record(query: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun count(): Int = 0
    }
}
