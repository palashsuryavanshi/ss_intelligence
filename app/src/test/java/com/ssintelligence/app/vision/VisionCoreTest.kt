package com.ssintelligence.app.vision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** dHash behavior on synthetic pixel arrays: no Bitmap, no device. */
class DHashImageEmbeddingProviderTest {

    private val provider = DHashImageEmbeddingProvider()

    private fun solid(width: Int, height: Int, color: Int): IntArray =
        IntArray(width * height) { color }

    private fun halfSplit(width: Int, height: Int, left: Int, right: Int): IntArray =
        IntArray(width * height) { i -> if ((i % width) < width / 2) left else right }

    @Test
    fun `identical images hash identically`() {
        val a = halfSplit(32, 32, 0xFFFFFF, 0x000000)
        val b = halfSplit(32, 32, 0xFFFFFF, 0x000000)
        assertEquals(
            provider.embed(a, 32, 32).hash,
            provider.embed(b, 32, 32).hash,
        )
        assertEquals(0, hammingDistance(provider.embed(a, 32, 32).hash, provider.embed(b, 32, 32).hash))
    }

    @Test
    fun `inverted layouts differ by their edge transitions`() {
        val a = halfSplit(32, 32, 0xFFFFFF, 0x000000)
        val b = halfSplit(32, 32, 0x000000, 0xFFFFFF)
        val distance = hammingDistance(
            provider.embed(a, 32, 32).hash,
            provider.embed(b, 32, 32).hash,
        )
        // dHash records brightness *transitions*, not absolute values: one
        // vertical edge produces two flipped comparisons per row, eight rows.
        // A dark-mode variant of the same screen therefore stays related
        // rather than reading as a different image — the desired behavior.
        assertEquals(16, distance)
    }

    @Test
    fun `a small price change is a near duplicate, a different page is not`() {
        // Two product listings differing in one digit block: mostly same rows.
        val base = IntArray(32 * 32) { i ->
            val x = i % 32
            val y = i / 32
            if (x in 8..24 && y in 8..24) 0xFFFFFF else 0x202020
        }
        val changed = base.copyOf().also {
            // Flip one 4x4 digit cell: a few rows change, most do not.
            for (y in 10..13) for (x in 10..13) it[y * 32 + x] = 0x000000
        }
        val other = IntArray(32 * 32) { i ->
            val x = i % 32
            val y = i / 32
            if ((x + y) % 2 == 0) 0xFFFFFF else 0x000000
        }
        val baseHash = provider.embed(base, 32, 32).hash
        val changedDistance = hammingDistance(baseHash, provider.embed(changed, 32, 32).hash)
        val otherDistance = hammingDistance(baseHash, provider.embed(other, 32, 32).hash)
        assertTrue("changed=$changedDistance should be near-duplicate", changedDistance <= ImageEmbedding.NEAR_DUPLICATE_BITS)
        assertTrue("other=$otherDistance should be dissimilar", otherDistance > ImageEmbedding.NEAR_DUPLICATE_BITS)
    }

    @Test
    fun `similarity is one for identical and partial for inverted`() {
        val a = provider.embed(halfSplit(32, 32, 0xFFFFFF, 0x000000), 32, 32)
        val same = provider.embed(halfSplit(32, 32, 0xFFFFFF, 0x000000), 32, 32)
        assertEquals(1.0, a.similarityTo(same), 0.001)
        val inverted = provider.embed(halfSplit(32, 32, 0x000000, 0xFFFFFF), 32, 32)
        // 16 bits differ of the 24-bit saturation range: related, not equal.
        assertEquals(1.0 - 16.0 / 24.0, a.similarityTo(inverted), 0.001)
    }

    @Test
    fun `brightness shifts keep similar images similar`() {
        val bright = halfSplit(32, 32, 0xFFFFFF, 0x808080)
        val dimmed = halfSplit(32, 32, 0xC0C0C0, 0x404040)
        val distance = hammingDistance(
            provider.embed(bright, 32, 32).hash,
            provider.embed(dimmed, 32, 32).hash,
        )
        // Same gradient direction in every row: dHash compares neighbours.
        assertEquals(0, distance)
    }

    @Test
    fun `metadata identifies the provider`() {
        assertEquals("dhash", provider.model)
        assertEquals("1", provider.version)
        assertTrue(provider.isAvailable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mixing image model versions is refused`() {
        val a = provider.embed(solid(16, 16, 0xFF0000), 16, 16)
        a.similarityTo(a.copy(version = "999"))
    }
}

/** Palette analysis on synthetic pixels. */
class ColorAnalysisTest {

    private fun solid(count: Int, color: Int): IntArray = IntArray(count) { color }

    @Test
    fun `a blue screen reports blue`() {
        val palette = ColorAnalysis.analyze(solid(1000, 0x2266DD))
        assertEquals(ColorAnalysis.NamedColor.BLUE, palette.colors.first())
        assertTrue(palette.topShare > 0.9)
        assertTrue(!palette.isDark)
    }

    @Test
    fun `a black screen is dark`() {
        val palette = ColorAnalysis.analyze(solid(1000, 0x0A0A0A))
        assertEquals(ColorAnalysis.NamedColor.BLACK, palette.colors.first())
        assertTrue(palette.isDark)
    }

    @Test
    fun `a white screen is bright and not dark`() {
        val palette = ColorAnalysis.analyze(solid(1000, 0xFFFFFF))
        assertEquals(ColorAnalysis.NamedColor.WHITE, palette.colors.first())
        assertTrue(!palette.isDark)
        assertTrue(palette.brightness > 200)
    }

    @Test
    fun `mixed pixels rank by share`() {
        val pixels = IntArray(1000) { i -> if (i < 700) 0xCC2222 else 0x2244CC }
        val palette = ColorAnalysis.analyze(pixels)
        assertEquals(ColorAnalysis.NamedColor.RED, palette.colors[0])
        assertEquals(ColorAnalysis.NamedColor.BLUE, palette.colors[1])
    }

    @Test
    fun `speckle below the share floor is ignored`() {
        val pixels = IntArray(1000) { i -> if (i < 50) 0xCC2222 else 0xFFFFFF }
        val palette = ColorAnalysis.analyze(pixels)
        assertTrue(ColorAnalysis.NamedColor.RED !in palette.colors)
    }

    @Test
    fun `empty input yields an empty palette`() {
        val palette = ColorAnalysis.analyze(IntArray(0))
        assertTrue(palette.colors.isEmpty())
        assertTrue(!palette.isDark)
    }

    @Test
    fun `query words resolve`() {
        assertEquals(ColorAnalysis.NamedColor.BLUE, ColorAnalysis.QUERY_WORDS["blue"])
        assertEquals(ColorAnalysis.NamedColor.GRAY, ColorAnalysis.QUERY_WORDS["grey"])
        assertEquals(null, ColorAnalysis.QUERY_WORDS["turquoise"])
    }
}

/** Layout geometry on synthetic boxes. */
class LayoutDetectorTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        NormRect(left, top, right, bottom)

    @Test
    fun `alternating short lines are chat`() {
        val blocks = listOf(
            box(60, 100, 400, 140),
            box(600, 160, 940, 200),
            box(60, 220, 380, 260),
            box(620, 280, 940, 320),
            box(60, 340, 420, 380),
        )
        assertEquals(ScreenLayout.CHAT_BUBBLES, LayoutDetector.detect(blocks))
    }

    @Test
    fun `column-aligned rows are a table`() {
        val blocks = listOf(
            box(60, 100, 200, 130), box(300, 100, 450, 130), box(550, 100, 700, 130),
            box(60, 150, 200, 180), box(300, 150, 450, 180), box(550, 150, 700, 180),
            box(60, 200, 200, 230), box(300, 200, 450, 230), box(550, 200, 700, 230),
        )
        assertEquals(ScreenLayout.TABLE, LayoutDetector.detect(blocks))
    }

    @Test
    fun `long full-width lines are an article`() {
        val blocks = (0 until 8).map { i -> box(40, 100 + i * 50, 960, 130 + i * 50) }
        assertEquals(ScreenLayout.ARTICLE, LayoutDetector.detect(blocks))
    }

    @Test
    fun `left-anchored stacks are a form`() {
        val blocks = (0 until 7).map { i -> box(80, 100 + i * 80, 500, 130 + i * 80) } +
            listOf(box(600, 100, 920, 130), box(600, 500, 920, 530))
        assertEquals(ScreenLayout.FORM_LIKE, LayoutDetector.detect(blocks))
    }

    @Test
    fun `too few blocks is none`() {
        assertEquals(ScreenLayout.NONE, LayoutDetector.detect(listOf(box(0, 0, 100, 20))))
        assertEquals(ScreenLayout.NONE, LayoutDetector.detect(emptyList()))
    }
}

/** Type classification from combined signals. */
class ScreenshotTypeClassifierTest {

    private fun base(ocr: String) = ScreenshotTypeClassifier.Input(
        ocrText = ocr,
        hosts = emptyList(),
        hasPrice = false,
        hasOtp = false,
        hasPhone = false,
        width = 1080,
        height = 2400,
        isDark = false,
        textCoverage = 0.3,
        layout = ScreenLayout.NONE,
    )

    @Test
    fun `a boarding pass is a ticket`() {
        val result = ScreenshotTypeClassifier.classify(
            base("IndiGo boarding pass PNR 7QK2LP departure gate").copy(
                hosts = listOf("indigo.in"),
            ),
        )
        assertEquals(ScreenshotType.TICKET, result.type)
    }

    @Test
    fun `a product page is a product listing`() {
        val result = ScreenshotTypeClassifier.classify(
            base("Google Pixel 9a Add to cart Free delivery ratings").copy(
                hosts = listOf("amazon.in"),
                hasPrice = true,
            ),
        )
        assertEquals(ScreenshotType.PRODUCT_LISTING, result.type)
    }

    @Test
    fun `an otp screen is banking`() {
        val result = ScreenshotTypeClassifier.classify(
            base("Your OTP is 483921 HDFC Bank account").copy(hasOtp = true),
        )
        assertEquals(ScreenshotType.BANKING, result.type)
    }

    @Test
    fun `a single banking word is not enough`() {
        val result = ScreenshotTypeClassifier.classify(base("account settings page"))
        assertTrue(result.type != ScreenshotType.BANKING)
    }

    @Test
    fun `a tall image is a long screenshot regardless of content`() {
        val result = ScreenshotTypeClassifier.classify(
            base("flight ticket booking").copy(width = 1080, height = 7000),
        )
        assertEquals(ScreenshotType.LONG_SCREENSHOT, result.type)
    }

    @Test
    fun `a textless image is a photo`() {
        val result = ScreenshotTypeClassifier.classify(
            base("").copy(textCoverage = 0.0),
        )
        assertEquals(ScreenshotType.PHOTO, result.type)
    }

    @Test
    fun `chat layout plus words is chat`() {
        val result = ScreenshotTypeClassifier.classify(
            base("typing Hey are you there message delivered").copy(
                layout = ScreenLayout.CHAT_BUBBLES,
            ),
        )
        assertEquals(ScreenshotType.CHAT, result.type)
    }

    @Test
    fun `generic app text is app ui`() {
        val result = ScreenshotTypeClassifier.classify(
            base("Settings Notifications Profile Search Home Save Done"),
        )
        assertEquals(ScreenshotType.APP_UI, result.type)
    }
}
