package com.ssintelligence.app.search

import com.ssintelligence.app.domain.model.ExtractedPhone
import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ExtractedUrl
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.search.parser.DateQueryParser
import com.ssintelligence.app.search.parser.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Deterministic relevance scoring (§18, §19, §20, §21).
 *
 * Every case here is about *order*, not about a magic number: the point is that
 * a combined match outranks a partial one, an exact price outranks a near one,
 * and a verbatim phrase outranks scattered words.
 */
class SearchRankerTest {

    private val ranker = SearchRanker()
    private val now = LocalDateTime.of(2026, 9, 30, 12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private val parser = QueryParser(
        dateParser = DateQueryParser(today = { LocalDate.of(2026, 9, 30) }),
    )

    private fun screenshot(
        id: Long,
        ocr: String,
        filename: String = "Screenshot_2026-09-28-10-00-00.png",
        daysAgo: Long = 2,
        duplicateOf: Long? = null,
    ): Screenshot {
        val added = now / 1000 - daysAgo * 86_400
        return Screenshot(
            id = id,
            mediaStoreId = 1000 + id,
            uri = "content://media/external/images/media/${1000 + id}",
            filename = filename,
            relativePath = "Pictures/Screenshots/",
            dateAdded = added,
            dateModified = added,
            fileSize = 200_000,
            width = 1080,
            height = 2400,
            mimeType = "image/png",
            ocrText = ocr,
            contentHash = "hash-$id",
            duplicateOfId = duplicateOf,
            status = ProcessingStatus.COMPLETED,
            error = null,
            createdAt = added * 1000,
            updatedAt = added * 1000,
        )
    }

    private fun candidate(
        id: Long,
        ocr: String,
        filename: String = "Screenshot_2026-09-28-10-00-00.png",
        daysAgo: Long = 2,
        prices: List<ExtractedPrice> = emptyList(),
        urls: List<ExtractedUrl> = emptyList(),
        phones: List<ExtractedPhone> = emptyList(),
        otps: List<String> = emptyList(),
        duplicateOf: Long? = null,
    ) = RankCandidate(
        screenshot = screenshot(id, ocr, filename, daysAgo, duplicateOf),
        prices = prices,
        urls = urls,
        phones = phones,
        otpCodes = otps,
    )

    private fun price(amount: Double, currency: String = Currency.INR) =
        ExtractedPrice(1, 1, "₹$amount", currency, amount)

    private fun url(host: String) = ExtractedUrl(1, 1, "https://$host/path", host)

    private fun phone(normalized: String) =
        ExtractedPhone(1, 1, normalized, normalized, "IN")

    /**
     * Ranks candidates for a query the way the app does: the real parser runs
     * first, so these cases exercise parse → rank together rather than
     * hand-building a query that could never be produced by a user.
     */
    private fun order(
        query: String,
        vararg candidates: RankCandidate,
    ): List<RankedResult> =
        ranker.rank(parser.parse(query), candidates.toList(), now)

    // ------------------------------------------------------- phrase (§19)

    @Test
    fun `an exact phrase outranks scattered words`() {
        val exact = candidate(
            1,
            ocr = "Google Pixel 9a\n5G 128GB\n₹39,999",
            prices = listOf(price(39999.0)),
        )
        val scattered = candidate(
            2,
            ocr = "Pixel phone with Android 9a update available now",
            prices = listOf(price(39999.0)),
        )
        val ranked = order("Pixel 9a", exact, scattered)
        assertEquals(1L, ranked.first().candidate.screenshot.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `a phrase broken by an OCR line break still matches`() {
        val wrapped = candidate(1, ocr = "Google Pixel\n9a 5G")
        val ranked = order("Pixel 9a", wrapped)
        assertTrue(
            ranked.first().reasons.any { it.kind == MatchKind.PHRASE },
        )
    }

    // ------------------------------------------------------- price (§20)

    @Test
    fun `an exact price outranks a near one`() {
        val exact = candidate(1, "Pixel 9a ₹39,999", prices = listOf(price(39999.0)))
        val close = candidate(2, "Pixel 9a ₹40,999", prices = listOf(price(40999.0)))
        val far = candidate(3, "Pixel 9a ₹49,999", prices = listOf(price(49999.0)))
        val ranked = order(
            "Pixel 9a for ₹39,999",
            exact,
            close,
            far,
        )
        assertEquals(listOf(1L, 2L, 3L), ranked.map { it.candidate.screenshot.id })
    }

    @Test
    fun `a different currency is not a price match`() {
        val dollars = candidate(1, "Laptop $1,299", prices = listOf(price(1299.0, Currency.USD)))
        val ranked = order("laptop ₹1,299", dollars)
        assertEquals(0, ranked.first().reasons.count { it.kind == MatchKind.PRICE })
    }

    // -------------------------------------------------- combined (§21)

    @Test
    fun `matching both the words and the price outranks matching only one`() {
        val both = candidate(
            1,
            "Pixel 9a 5G 128GB ₹39,999",
            prices = listOf(price(39999.0)),
        )
        val wordsOnly = candidate(2, "Pixel 9a 5G 128GB ₹42,999", prices = listOf(price(42999.0)))
        val priceOnly = candidate(3, "Nothing else here at all", prices = listOf(price(39999.0)))
        val ranked = order("Find the screenshot where I saw Pixel 9a for ₹39,999", both, wordsOnly, priceOnly)
        assertEquals(1L, ranked.first().candidate.screenshot.id)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    // ----------------------------------------------------------- URLs

    @Test
    fun `a parent domain matches a subdomain`() {
        val subdomain = candidate(1, "Great deal", urls = listOf(url("smile.amazon.in")))
        val other = candidate(2, "Different site", urls = listOf(url("flipkart.com")))
        val ranked = order("screenshots from amazon.in", subdomain, other)
        assertEquals(1L, ranked.first().candidate.screenshot.id)
    }

    @Test
    fun `a domain must not match a lookalike host`() {
        val lookalike = candidate(1, "Deal", urls = listOf(url("notamazon.in")))
        val ranked = order("screenshots from amazon.in", lookalike)
        assertTrue(ranked.none { it.reasons.any { reason -> reason.kind == MatchKind.URL } })
    }

    // ------------------------------------------------------ phones, codes

    @Test
    fun `a phone match is exact only`() {
        val exact = candidate(1, "Call us", phones = listOf(phone("+919876543210")))
        val different = candidate(2, "Call us", phones = listOf(phone("+919876543211")))
        val ranked = order("find screenshot containing 9876543210", exact, different)
        assertEquals(1L, ranked.first().candidate.screenshot.id)
        assertEquals(0, ranked[1].reasons.count { it.kind == MatchKind.PHONE })
    }

    @Test
    fun `a code match never reveals the value`() {
        val withCode = candidate(1, "Your code", otps = listOf("483921"))
        val ranked = order("find the screenshot with OTP 483921", withCode)
        val reason = ranked.first().reasons.single { it.kind == MatchKind.OTP }
        assertEquals("One-time code", reason.label)
        assertTrue(ranked.first().reasons.none { it.label.contains("483921") })
    }

    // ------------------------------------------------------------ dates

    @Test
    fun `a date filter contributes a reason`() {
        val start = LocalDate.of(2026, 9, 1)
        val end = LocalDate.of(2026, 9, 30)
        val query = SearchQuery(
            originalQuery = "from September",
        dateFilters = listOf(
            DateFilter(start.toEpochDay(), end.toEpochDay(), "September 2026"),
        ),
        timeRange = TimeRange.days(start, end),
    )
    val inside = candidate(1, "anything", daysAgo = 2)
    val ranked = ranker.rank(query, listOf(inside), now)
        assertTrue(ranked.first().reasons.any { it.kind == MatchKind.DATE })
    }

    // ---------------------------------------------------------- ordering

    @Test
    fun `a filename hit is worth a small bonus`() {
        // Both rows contain the term only once; the only difference is whether
        // the term appears in the filename.
        val named = candidate(1, "nothing relevant here", filename = "airpods.png")
        val plain = candidate(2, "nothing relevant here", filename = "Screenshot_1.png")
        val ranked = order("airpods", named, plain)
        assertEquals(1L, ranked.first().candidate.screenshot.id)
        assertTrue(ranked.first().reasons.any { it.kind == MatchKind.FILENAME })
        assertTrue(ranked.last().reasons.none { it.kind == MatchKind.FILENAME })
    }

    @Test
    fun `recency breaks a tie but never outranks a match`() {
        val old = candidate(1, "Pixel 9a", daysAgo = 300)
        val recent = candidate(2, "Pixel 9a", daysAgo = 1)
        val tied = order("Pixel 9a", old, recent)
        assertEquals(2L, tied.first().candidate.screenshot.id)

        val oldMatch = candidate(3, "Pixel 9a ₹39,999", daysAgo = 300, prices = listOf(price(39999.0)))
        val newMiss = candidate(4, "totally different", daysAgo = 0)
        val ranked = order("Pixel 9a for ₹39,999", oldMatch, newMiss)
        assertEquals(3L, ranked.first().candidate.screenshot.id)
    }

    @Test
    fun `ranking is a total order so results never swap between runs`() {
        val a = candidate(1, "Pixel 9a", daysAgo = 5)
        val b = candidate(2, "Pixel 9a", daysAgo = 5)
        val first = order("pixel 9a", a, b).map { it.candidate.screenshot.id }
        val second = order("pixel 9a", b, a).map { it.candidate.screenshot.id }
        assertEquals(first, second)
    }

    @Test
    fun `weights are configurable`() {
        val custom = SearchRanker(RelevanceWeights(exactPhrase = 0, allTerms = 0, ocrMatch = 1))
        val scored = custom.rank(
            SearchQuery(originalQuery = "pixel 9a", phrases = listOf("pixel 9a"), textTerms = listOf("pixel", "9a")),
            listOf(candidate(1, "Pixel 9a 5G")),
            now,
        )
        // With every text weight at zero only the recency bonus remains.
        assertTrue(scored.first().score < 20)
    }

    @Test
    fun `an empty candidate list ranks to nothing`() {
        assertTrue(order("pixel 9a").isEmpty())
    }

    @Test
    fun `every result carries a snippet for display`() {
        val ranked = order("pixel 9a", candidate(1, "Google Pixel 9a 5G 128GB"))
        val snippet = ranked.first().snippet
        assertTrue(snippet != null && snippet.text.contains("Pixel"))
    }
}
