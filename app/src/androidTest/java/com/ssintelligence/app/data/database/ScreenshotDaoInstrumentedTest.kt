package com.ssintelligence.app.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Database behaviour: insert, update, delete, search and duplicate lookup
 * (§36). Instrumented because Room needs a real SQLite engine.
 *
 * Run with: ./gradlew connectedDebugAndroidTest
 *
 * Test names are plain camelCase rather than backticked sentences: D8 rejects
 * spaces in the generated inner-class names for methods with backticked names
 * containing spaces.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotDaoInstrumentedTest {

    private lateinit var database: SsIntelligenceDatabase
    private lateinit var dao: ScreenshotDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SsIntelligenceDatabase::class.java,
        ).build()
        dao = database.screenshotDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun row(
        mediaStoreId: Long,
        filename: String,
        ocrText: String = "",
        status: String = "COMPLETED",
        contentHash: String? = null,
        duplicateOfId: Long? = null,
        dateAdded: Long = 1_700_000_000,
        fileSize: Long = 1024,
        dateModified: Long = dateAdded,
    ) = ScreenshotEntity(
        mediaStoreId = mediaStoreId,
        uri = "content://media/external/images/media/$mediaStoreId",
        filename = filename,
        relativePath = "Pictures/Screenshots",
        dateAdded = dateAdded,
        dateModified = dateModified,
        fileSize = fileSize,
        width = 1080,
        height = 2400,
        mimeType = "image/png",
        ocrText = ocrText,
        contentHash = contentHash,
        duplicateOfId = duplicateOfId,
        status = status,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    private suspend fun search(term: String, filter: String = "ALL"): List<ScreenshotEntity> {
        // Mirrors FtsQueryBuilder: each whitespace-separated term becomes a
        // quoted prefix phrase, combined with the implicit AND.
        val ftsQuery = term.split(" ")
            .filter { it.length >= 2 }
            .joinToString(" ") { "\"$it\"*" }
        return dao.search(
            ftsQuery = ftsQuery,
            lowerQuery = term,
            prefixQuery = "$term%",
            containsQuery = "%$term%",
            filterType = filter,
            limit = 10,
        ).first().map { it.screenshot }
    }

    @Test
    fun insertAndReadBack() = runBlocking {
        val inserted = dao.insertIgnoring(listOf(row(mediaStoreId = 1, filename = "Screenshot_1.png")))
        assertEquals(1, inserted.size)
        val stored = dao.getById(inserted.first())
        assertNotNull(stored)
        assertEquals("Screenshot_1.png", stored?.filename)
    }

    @Test
    fun insertIsIdempotentOnMediaStoreId() = runBlocking {
        dao.insertIgnoring(listOf(row(mediaStoreId = 7, filename = "a.png")))
        val second = dao.insertIgnoring(listOf(row(mediaStoreId = 7, filename = "a.png")))
        assertTrue(second.all { it == -1L })
    }

    @Test
    fun updateChangesStoredValues() = runBlocking {
        val id = dao.insertIgnoring(listOf(row(mediaStoreId = 2, filename = "old.png"))).first()
        dao.markCompleted(id, "new text", "hash-1", null, 1_700_000_000_000)
        val updated = dao.getById(id)
        assertEquals("new text", updated?.ocrText)
        assertEquals("hash-1", updated?.contentHash)
        assertEquals("COMPLETED", updated?.status)
    }

    @Test
    fun deleteRemovesTheRow() = runBlocking {
        val id = dao.insertIgnoring(listOf(row(mediaStoreId = 3, filename = "gone.png"))).first()
        dao.deleteById(id)
        assertNull(dao.getById(id))
    }

    @Test
    fun deletedScreenshotCascadesToExtractedInformation() = runBlocking {
        val id = dao.insertIgnoring(listOf(row(mediaStoreId = 4, filename = "x.png"))).first()
        dao.insertPrices(
            listOf(
                ExtractedPriceEntity(
                    screenshotId = id,
                    rawText = "INR 10",
                    currency = "INR",
                    amount = 10.0,
                )
            )
        )
        assertEquals(1, dao.observePrices(id).first().size)
        dao.deleteById(id)
        assertTrue(dao.observePrices(id).first().isEmpty())
    }

    @Test
    fun searchMatchesOcrTextCaseInsensitively() = runBlocking {
        dao.insertIgnoring(listOf(row(mediaStoreId = 10, filename = "a.png", ocrText = "Pixel 9a 8GB 128GB")))
        dao.insertIgnoring(listOf(row(mediaStoreId = 11, filename = "b.png", ocrText = "Nothing relevant")))
        val results = search("pixel")
        assertEquals(1, results.size)
        assertEquals("a.png", results.first().filename)
    }

    @Test
    fun searchMatchesMultipleTermsAsAnd() = runBlocking {
        dao.insertIgnoring(listOf(row(mediaStoreId = 12, filename = "a.png", ocrText = "Pixel 9a 8GB")))
        dao.insertIgnoring(listOf(row(mediaStoreId = 13, filename = "b.png", ocrText = "Pixel 10 Pro")))

        // FTS4 ANDs whitespace-separated phrases, and rejects the explicit
        // `AND` keyword — so the query builder must use the implicit form.
        val matches = search("pixel 9a").map { it.filename }
        assertEquals(listOf("a.png"), matches)

        // Explicit AND must not be used: it matches nothing on FTS4.
        val explicit = dao.search(
            ftsQuery = "\"pixel\"* AND \"9a\"*",
            lowerQuery = "pixel 9a",
            prefixQuery = "pixel 9a%",
            containsQuery = "%pixel 9a%",
            filterType = "ALL",
            limit = 10,
        ).first()
        assertTrue("explicit AND is expected to be unsupported on FTS4", explicit.isEmpty())
    }

    @Test
    fun filenameMatchRanksAboveOcrTextMatch() = runBlocking {
        dao.insertIgnoring(listOf(row(mediaStoreId = 20, filename = "receipt.png", ocrText = "unrelated")))
        dao.insertIgnoring(listOf(row(mediaStoreId = 21, filename = "other.png", ocrText = "a receipt total")))
        val results = dao.search(
            ftsQuery = "\"receipt\"*",
            lowerQuery = "receipt",
            prefixQuery = "receipt%",
            containsQuery = "%receipt%",
            filterType = "ALL",
            limit = 10,
        ).first()
        assertEquals(2, results.size)
        assertEquals("receipt.png", results.first().screenshot.filename)
        assertTrue(results.first().score > results[1].score)
    }

    @Test
    fun filterRestrictsSearchToMatchingExtractedInformation() = runBlocking {
        val withPrice = dao.insertIgnoring(
            listOf(row(mediaStoreId = 30, filename = "a.png", ocrText = "phone deal"))
        ).first()
        dao.insertIgnoring(listOf(row(mediaStoreId = 31, filename = "b.png", ocrText = "phone deal")))
        dao.insertPrices(
            listOf(
                ExtractedPriceEntity(
                    screenshotId = withPrice,
                    rawText = "INR 39,999",
                    currency = "INR",
                    amount = 39999.0,
                )
            )
        )
        val results = search("phone", filter = "PRICES")
        assertEquals(1, results.size)
        assertEquals(withPrice, results.first().id)
    }

    @Test
    fun duplicateFilterSelectsRowsLinkedToCanonicalCopy() = runBlocking {
        val canonical = dao.insertIgnoring(
            listOf(row(mediaStoreId = 40, filename = "first.png", contentHash = "same"))
        ).first()
        dao.insertIgnoring(
            listOf(row(mediaStoreId = 41, filename = "copy.png", contentHash = "same", duplicateOfId = canonical))
        )
        dao.insertIgnoring(listOf(row(mediaStoreId = 42, filename = "unique.png", contentHash = "other")))
        val duplicates = dao.observeFiltered("DUPLICATES", 10).first()
        assertEquals(1, duplicates.size)
        assertEquals("copy.png", duplicates.first().filename)
    }

    @Test
    fun duplicateLookupReturnsTheCanonicalCompletedCopy() = runBlocking {
        val canonical = dao.insertIgnoring(
            listOf(row(mediaStoreId = 50, filename = "first.png", contentHash = "same"))
        ).first()
        dao.insertIgnoring(listOf(row(mediaStoreId = 51, filename = "second.png", contentHash = "same")))
        assertEquals(canonical, dao.findCompletedByHash("same", excludeId = 0)?.id)
    }

    @Test
    fun duplicateLookupExcludesTheRequestedRow() = runBlocking {
        val id = dao.insertIgnoring(
            listOf(row(mediaStoreId = 52, filename = "only.png", contentHash = "same"))
        ).first()
        assertNull(dao.findCompletedByHash("same", excludeId = id))
    }

    @Test
    fun pendingQueueIsServedOldestFirstAndExcludesInFlightRows() = runBlocking {
        dao.insertIgnoring(
            listOf(
                row(mediaStoreId = 60, filename = "newer.png", status = "PENDING", dateAdded = 200),
                row(mediaStoreId = 61, filename = "older.png", status = "PENDING", dateAdded = 100),
                row(mediaStoreId = 62, filename = "busy.png", status = "PROCESSING", dateAdded = 50),
                row(mediaStoreId = 63, filename = "done.png", status = "COMPLETED", dateAdded = 60),
            )
        )
        assertEquals(listOf("older.png", "newer.png"), dao.nextPending(10).map { it.filename })
    }

    @Test
    fun staleProcessingRowsAreReturnedToTheQueue() = runBlocking {
        dao.insertIgnoring(
            listOf(row(mediaStoreId = 70, filename = "interrupted.png", status = "PROCESSING"))
        )
        assertEquals(1, dao.resetStaleProcessing(1_700_000_000_000))
        assertEquals(1, dao.nextPending(10).size)
    }

    @Test
    fun changedMediaIsRequeuedForReprocessing() = runBlocking {
        val id = dao.insertIgnoring(
            listOf(row(mediaStoreId = 80, filename = "a.png", status = "COMPLETED", dateAdded = 100))
        ).first()
        dao.markCompleted(id, "text", "h", null, 0)
        val updated = dao.refreshChanged(
            mediaStoreId = 80,
            filename = "a.png",
            relativePath = "Pictures/Screenshots",
            dateAdded = 100,
            dateModified = 200,
            fileSize = 4096,
            width = 1080,
            height = 2400,
            mimeType = "image/png",
            now = 0,
        )
        assertEquals(1, updated)
        assertEquals("PENDING", dao.getByMediaStoreId(80)?.status)
    }

    @Test
    fun unchangedCompletedMediaIsLeftAlone() = runBlocking {
        val id = dao.insertIgnoring(
            listOf(row(mediaStoreId = 90, filename = "a.png", status = "COMPLETED", dateAdded = 100))
        ).first()
        dao.markCompleted(id, "text", "h", null, 0)
        val updated = dao.refreshChanged(
            mediaStoreId = 90,
            filename = "a.png",
            relativePath = "Pictures/Screenshots",
            dateAdded = 100,
            dateModified = 100,
            fileSize = 1024,
            width = 1080,
            height = 2400,
            mimeType = "image/png",
            now = 0,
        )
        assertEquals(0, updated)
        assertEquals("COMPLETED", dao.getByMediaStoreId(90)?.status)
    }

    @Test
    fun fullTextIndexStaysInSyncWhenTextChanges() = runBlocking {
        val id = dao.insertIgnoring(
            listOf(row(mediaStoreId = 100, filename = "a.png", ocrText = "alpha"))
        ).first()
        dao.markCompleted(id, "alpha", "h", null, 0)
        assertEquals(1, search("alpha").size)

        dao.markCompleted(id, "beta", "h", null, 0)
        assertTrue("a stale term must leave the index", search("alpha").isEmpty())
        assertEquals(1, search("beta").size)
    }

    @Test
    fun keysetPaginationReturnsDistinctSuccessivePages() = runBlocking {
        repeat(30) { i ->
            dao.insertIgnoring(
                listOf(row(mediaStoreId = 200L + i, filename = "shot_$i.png", dateAdded = i.toLong()))
            )
        }
        // dateAdded == index, so ordering by date_added DESC gives shot_29..shot_0.
        val first = dao.getPage(Long.MAX_VALUE, Long.MAX_VALUE, limit = 20)
        assertEquals(20, first.size)
        assertEquals("shot_29.png", first.first().filename)
        assertEquals("shot_10.png", first.last().filename)

        val second = dao.getPage(first.last().dateAdded, first.last().id, limit = 20)
        assertEquals(10, second.size)
        assertEquals("shot_9.png", second.first().filename)
        assertEquals("shot_0.png", second.last().filename)
    }

    @Test
    fun statusCountsFeedTheHomeScreenTotals() = runBlocking {
        dao.insertIgnoring(
            listOf(
                row(mediaStoreId = 300, filename = "a.png", status = "COMPLETED"),
                row(mediaStoreId = 301, filename = "b.png", status = "PENDING"),
                row(mediaStoreId = 302, filename = "c.png", status = "FAILED"),
            )
        )
        val counts = dao.statusCounts().associate { it.status to it.count }
        assertEquals(1, counts["COMPLETED"])
        assertEquals(1, counts["PENDING"])
        assertEquals(1, counts["FAILED"])
    }

    @Test
    fun duplicateItemAndGroupCountsAreReportedSeparately() = runBlocking {
        val canonical = dao.insertIgnoring(
            listOf(row(mediaStoreId = 400, filename = "a.png", contentHash = "same"))
        ).first()
        dao.insertIgnoring(
            listOf(row(mediaStoreId = 401, filename = "b.png", contentHash = "same", duplicateOfId = canonical))
        )
        dao.insertIgnoring(listOf(row(mediaStoreId = 402, filename = "c.png", contentHash = "other")))
        assertEquals(1, dao.observeDuplicateItemCount().first())
        assertEquals(1, dao.observeDuplicateGroupCount().first())
    }

    @Test
    fun rebuildClearsDerivedDataAndRequeuesEveryRow() = runBlocking {
        val id = dao.insertIgnoring(
            listOf(row(mediaStoreId = 500, filename = "a.png", ocrText = "text", status = "COMPLETED"))
        ).first()
        dao.insertPrices(
            listOf(
                ExtractedPriceEntity(
                    screenshotId = id,
                    rawText = "INR 1",
                    currency = "INR",
                    amount = 1.0,
                )
            )
        )
        dao.requeueAllForReprocessing(1_700_000_000_000)
        assertTrue(dao.observePrices(id).first().isEmpty())
        assertEquals(1, dao.pendingCount())
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        dao.insertIgnoring(listOf(row(mediaStoreId = 600, filename = "a.png")))
        dao.clearAll()
        assertNull(dao.getById(1))
        assertEquals(0, dao.pendingCount())
    }

    @Test
    fun otpValuesAreNotReachableThroughSearch() = runBlocking {
        val id = dao.insertIgnoring(
            listOf(row(mediaStoreId = 700, filename = "a.png", ocrText = "Verify your number"))
        ).first()
        dao.insertOtps(listOf(ExtractedOtpEntity(screenshotId = id, code = "483921")))
        assertTrue("OTP text must not enter the FTS index", search("483921").isEmpty())
        // ...but the structured table still holds it for the detail screen.
        assertEquals(1, dao.observeOtps(id).first().size)
    }

    @Test
    fun requeueReturnsOneScreenshotToPending() = runBlocking {
        val id = dao.insertIgnoring(
            listOf(row(mediaStoreId = 800, filename = "failed.png", status = "FAILED"))
        ).first()
        assertEquals(1, dao.requeue(id, 0))
        assertEquals("PENDING", dao.getById(id)?.status)
    }
}
