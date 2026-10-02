package com.ssintelligence.app.compare

import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ExtractedUrl
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.ScreenshotDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR/metadata comparison: what changed between two screenshots. */
class CompareBuilderTest {

    private fun screenshot(id: Long, ocr: String) = Screenshot(
        id = id,
        mediaStoreId = id,
        uri = "content://x/$id",
        filename = "s$id.png",
        relativePath = null,
        dateAdded = 1_700_000_000L,
        dateModified = 1_700_000_000L,
        fileSize = 100,
        width = 1080,
        height = 2400,
        mimeType = "image/png",
        ocrText = ocr,
        contentHash = "h$id",
        duplicateOfId = null,
        status = ProcessingStatus.COMPLETED,
        error = null,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun detail(
        id: Long,
        ocr: String,
        prices: List<Pair<String, Double>> = emptyList(),
        hosts: List<String> = emptyList(),
    ) = ScreenshotDetail(
        screenshot = screenshot(id, ocr),
        urls = hosts.mapIndexed { i, host -> ExtractedUrl(i.toLong(), id, "https://$host/", host) },
        dates = emptyList(),
        phones = emptyList(),
        prices = prices.mapIndexed { i, (currency, amount) ->
            ExtractedPrice(i.toLong(), id, "$currency $amount", currency, amount)
        },
        otps = emptyList(),
        receipt = null,
    )

    @Test
    fun `a moved price is stated as a change`() {
        val comparison = CompareBuilder.compare(
            detail(1, "Pixel 9a ₹39,999", listOf("INR" to 39999.0), listOf("amazon.in")),
            detail(2, "Pixel 9a ₹41,999", listOf("INR" to 41999.0), listOf("amazon.in")),
        )
        assertEquals(listOf("Price ₹39999 → ₹41999"), comparison.changes)
        assertTrue(comparison.onlyInFirstHosts.isEmpty())
    }

    @Test
    fun `a moved website is stated as a change`() {
        val comparison = CompareBuilder.compare(
            detail(1, "Pixel 9a", emptyList(), listOf("amazon.in")),
            detail(2, "Pixel 9a", emptyList(), listOf("flipkart.com")),
        )
        assertEquals(listOf("Website amazon.in → flipkart.com"), comparison.changes)
    }

    @Test
    fun `different price counts are additions not moves`() {
        val comparison = CompareBuilder.compare(
            detail(1, "Phones", listOf("INR" to 10000.0)),
            detail(2, "Phones", listOf("INR" to 10000.0, "INR" to 20000.0)),
        )
        assertTrue(comparison.changes.isEmpty())
        assertEquals(listOf("INR 20000.0"), comparison.onlyInSecondPrices)
    }

    @Test
    fun `added and removed terms are reported`() {
        val comparison = CompareBuilder.compare(
            detail(1, "Pixel 9a smartphone discount sale"),
            detail(2, "Pixel 9a smartphone offer cashback"),
        )
        assertTrue("offer" in comparison.addedTerms)
        assertTrue("cashback" in comparison.addedTerms)
        assertTrue("discount" in comparison.removedTerms)
        assertTrue("sale" in comparison.removedTerms)
        assertTrue("pixel" !in comparison.addedTerms)
    }

    @Test
    fun `identical screenshots have no differences`() {
        val comparison = CompareBuilder.compare(
            detail(1, "Pixel 9a ₹39,999", listOf("INR" to 39999.0), listOf("amazon.in")),
            detail(2, "Pixel 9a ₹39,999", listOf("INR" to 39999.0), listOf("amazon.in")),
        )
        assertTrue(comparison.changes.isEmpty())
        assertTrue(comparison.addedTerms.isEmpty())
        assertTrue(comparison.removedTerms.isEmpty())
    }

    @Test
    fun `currencies never mix in a change statement`() {
        val comparison = CompareBuilder.compare(
            detail(1, "Laptop", listOf("USD" to 1299.0)),
            detail(2, "Laptop", listOf("INR" to 1299.0)),
        )
        assertTrue(comparison.changes.isEmpty())
    }
}
