package com.ssintelligence.app.vision

/**
 * Palette analysis over downscaled pixels (§9).
 *
 * Reports which named colors a screenshot is *mostly* made of — background
 * tints, app themes, product photos — never what any object *is*. A screenshot
 * that is largely blue is "blue", whether the blue is a sky, a button, or a
 * phone. That honesty is what makes color search defensible: the query "blue"
 * matches palettes containing blue, and the UI says exactly that.
 */
object ColorAnalysis {

    /** Named colors with their sRGB centers. Small on purpose: coarse and stable. */
    enum class NamedColor(val label: String, val r: Int, val g: Int, val b: Int) {
        RED("red", 200, 40, 40),
        ORANGE("orange", 230, 130, 20),
        YELLOW("yellow", 230, 210, 60),
        GREEN("green", 50, 170, 80),
        BLUE("blue", 50, 120, 220),
        PURPLE("purple", 130, 70, 190),
        PINK("pink", 230, 130, 180),
        BROWN("brown", 140, 100, 60),
        BLACK("black", 20, 20, 20),
        WHITE("white", 240, 240, 240),
        GRAY("gray", 140, 140, 140),
    }

    /** Words the query parser understands as colors, mapped to [NamedColor]. */
    val QUERY_WORDS: Map<String, NamedColor> = buildMap {
        for (color in NamedColor.entries) put(color.label, color)
        put("grey", NamedColor.GRAY)
        put("violet", NamedColor.PURPLE)
        put("cyan", NamedColor.BLUE)
        put("teal", NamedColor.GREEN)
        put("maroon", NamedColor.RED)
        put("beige", NamedColor.BROWN)
    }

    /**
     * Brightness words. Not colors — they match the dark/light flag, not the
     * palette — but they travel the same query path, so they live here next to
     * the color vocabulary.
     */
    val BRIGHTNESS_WORDS = setOf("dark", "light")

    data class Palette(
        /** Top colors by pixel share, most common first. */
        val colors: List<NamedColor>,
        /** Share of the single most common color, 0..1. */
        val topShare: Double,
        /** Mean luminance, 0..255. */
        val brightness: Double,
        /** True for dark mode screenshots, OLED blacks, night photos. */
        val isDark: Boolean,
    )

    /**
     * @param pixels row-major `0xRRGGBB` ints, already downscaled (a few
     *   thousand pixels is plenty — palettes don't need resolution).
     */
    fun analyze(pixels: IntArray): Palette {
        if (pixels.isEmpty()) {
            return Palette(emptyList(), 0.0, 0.0, false)
        }
        val counts = IntArray(NamedColor.entries.size)
        var luminanceSum = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            luminanceSum += (r * 77 + g * 150 + b * 29) shr 8
            counts[nearest(r, g, b).ordinal]++
        }
        val total = pixels.size.toDouble()
        val ranked = counts.indices
            .sortedByDescending { counts[it] }
            .filter { counts[it] / total >= MIN_SHARE }
            .take(MAX_COLORS)
            .map { NamedColor.entries[it] }
        val brightness = luminanceSum / total
        return Palette(
            colors = ranked,
            topShare = (counts.maxOrNull() ?: 0) / total,
            brightness = brightness,
            // Below this mean luminance the screenshot reads as dark: dark
            // mode UI, night scenes, black backgrounds.
            isDark = brightness < DARK_THRESHOLD,
        )
    }

    private fun nearest(r: Int, g: Int, b: Int): NamedColor {
        var best = NamedColor.GRAY
        var bestDistance = Int.MAX_VALUE
        for (color in NamedColor.entries) {
            val dr = r - color.r
            val dg = g - color.g
            val db = b - color.b
            // Weighted Euclidean: green carries most of perceived brightness.
            val distance = dr * dr * 2 + dg * dg * 4 + db * db * 3
            if (distance < bestDistance) {
                bestDistance = distance
                best = color
            }
        }
        return best
    }

    /** Below this share a color is speckle, not palette. */
    private const val MIN_SHARE = 0.08

    private const val MAX_COLORS = 3

    private const val DARK_THRESHOLD = 90.0
}

/** Screenshot types, combining OCR, metadata and visual signals (§10). */
enum class ScreenshotType(val label: String) {
    APP_UI("App screen"),
    WEBPAGE("Web page"),
    CHAT("Chat"),
    DOCUMENT("Document"),
    RECEIPT("Receipt"),
    PRODUCT_LISTING("Product listing"),
    TICKET("Ticket"),
    MAP("Map"),
    PHOTO("Photo"),
    SOCIAL_MEDIA("Social"),
    BANKING("Banking"),
    LONG_SCREENSHOT("Long screenshot"),
    OTHER("Other"),
}

/** Layout structures detected from OCR block geometry (§11). */
enum class ScreenLayout(val label: String) {
    CHAT_BUBBLES("Chat bubbles"),
    TABLE("Table"),
    RECEIPT_LIKE("Receipt"),
    FORM_LIKE("Form"),
    ARTICLE("Article"),
    NONE("None"),
}

/**
 * Everything visual analysis produces for one screenshot.
 *
 * All fields are plain data, recomputed deterministically from the image plus
 * the OCR geometry Phase 1 stores. Nothing is a model opinion.
 */
data class VisualAnalysis(
    val imageHash: Long,
    val colors: List<String>,
    val brightness: Double,
    val isDark: Boolean,
    /** Share of the image covered by OCR text boxes, 0..1. */
    val textCoverage: Double,
    val type: ScreenshotType,
    val layout: ScreenLayout,
    /** True when the image is unusually tall (§25). */
    val isLongScreenshot: Boolean,
    val modelVersion: String,
) {
    companion object {
        const val MODEL_VERSION = "visual-v1"

        /**
         * Height at least this many times the width counts as "long". Phone
         * screenshots are ~2.2:1; stitch-merged screenshots run 3:1 and up.
         */
        const val LONG_ASPECT = 2.8
    }
}
