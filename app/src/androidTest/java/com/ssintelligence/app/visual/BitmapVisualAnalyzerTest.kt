package com.ssintelligence.app.visual

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssintelligence.app.domain.model.OcrBlock
import com.ssintelligence.app.domain.model.OcrLevel
import com.ssintelligence.app.vision.BitmapVisualAnalyzer
import com.ssintelligence.app.vision.ScreenLayout
import com.ssintelligence.app.vision.ScreenshotType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The analyzer over synthetic pixels: palette, hash, coverage, type, layout.
 *
 * Bitmap decoding itself is thin platform glue (decode → pixels → recycle);
 * what matters here is that pixels become the right analysis. Runs without a
 * device GPU, a model, or the network.
 */
@RunWith(AndroidJUnit4::class)
class BitmapVisualAnalyzerTest {

    private fun analyzer(): BitmapVisualAnalyzer {
        // ContentResolver is only needed for URI decodes; analyzePixels is pure.
        val resolver = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>().contentResolver
        return BitmapVisualAnalyzer(resolver, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    private fun block(text: String, left: Int, top: Int, right: Int, bottom: Int) = OcrBlock(
        level = OcrLevel.LINE,
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        confidence = 0.9f,
    )

    @Test
    fun bluePixelsYieldBluePaletteAndHash() {
        val pixels = IntArray(64 * 64) { 0x2266DD }
        val analysis = analyzer().analyzePixels(pixels, 64, 64, emptyList(), 1080, 2400)

        assertEquals(listOf("blue"), analysis.colors)
        assertTrue(!analysis.isDark)
        assertEquals(0.0, analysis.textCoverage, 0.001)
    }

    @Test
    fun textCoverageIsResolutionIndependent() {
        val blocks = listOf(block("Hello world", 100, 100, 900, 150))
        // 800x50 of 1,000,000 normalized units = 4%.
        val analysis = analyzer().analyzePixels(
            IntArray(32 * 32) { 0xFFFFFF },
            32, 32, blocks, 1080, 2400,
        )
        assertEquals(0.04, analysis.textCoverage, 0.001)
    }

    @Test
    fun chatBlocksYieldChatLayoutAndType() {
        val blocks = listOf(
            block("hey", 60, 100, 400, 140),
            block("hi there", 600, 160, 940, 200),
            block("how are you", 60, 220, 380, 260),
            block("good thanks", 620, 280, 940, 320),
            block("typing message reply", 60, 340, 420, 380),
        )
        val pixels = IntArray(32 * 32) { 0xFFFFFF }
        val analysis = analyzer().analyzePixels(pixels, 32, 32, blocks, 1080, 2400)

        assertEquals(ScreenLayout.CHAT_BUBBLES, analysis.layout)
        assertEquals(ScreenshotType.CHAT, analysis.type)
    }

    @Test
    fun tallImagesAreLongScreenshots() {
        val analysis = analyzer().analyzePixels(
            IntArray(16 * 16) { 0xFFFFFF },
            16, 16, emptyList(), 1080, 7000,
        )
        assertTrue(analysis.isLongScreenshot)
        assertEquals(ScreenshotType.LONG_SCREENSHOT, analysis.type)
    }

    @Test
    fun analysisIsDeterministic() {
        val pixels = IntArray(32 * 32) { i -> if (i % 2 == 0) 0x112233 else 0x445566 }
        val blocks = listOf(block("Total ₹1,250 paid invoice", 100, 800, 500, 840))
        val first = analyzer().analyzePixels(pixels, 32, 32, blocks, 1080, 2400)
        val second = analyzer().analyzePixels(pixels, 32, 32, blocks, 1080, 2400)
        assertEquals(first, second)
    }

    @Test
    fun receiptTextYieldsReceiptType() {
        val blocks = listOf(
            block("STORE", 300, 60, 500, 90),
            block("Item A", 280, 120, 520, 150),
            block("Item B", 280, 160, 520, 190),
            block("Total", 280, 220, 520, 250),
            block("Paid", 280, 260, 520, 290),
            block("Invoice GSTIN", 260, 300, 540, 330),
        )
        val analysis = analyzer().analyzePixels(
            IntArray(32 * 32) { 0xFFFFFF },
            32, 32, blocks, 1080, 2400,
        )
        assertEquals(ScreenshotType.RECEIPT, analysis.type)
    }
}
