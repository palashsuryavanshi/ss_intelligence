package com.ssintelligence.app.vision

/**
 * Visual understanding without a neural model (§4–§11).
 *
 * Two separate questions, two separate components:
 * - [ImageEmbeddingProvider]: "what other images look like this?" Answered by
 *   a perceptual hash — pixels, not words. A neural CLIP-style model can take
 *   over this interface later without changing storage, search or UI.
 * - [VisualAnalyzer]: "what is in this image?" Answered deterministically from
 *   downscaled pixels plus the OCR geometry Phase 1 already stores: dominant
 *   colors, brightness, screenshot type, layout, long-screenshot detection.
 *
 * Nothing here claims object recognition. A blue rectangle is reported as blue
 * pixels, never as "a blue phone" — the day a model can actually see phones,
 * the analyzer interface is ready for it, and not before (§8).
 */

/** A visual embedding: 64 bits of perceptual hash plus its provenance. */
data class ImageEmbedding(
    /** 64-bit dHash. Hamming distance is visual dissimilarity. */
    val hash: Long,
    val model: String,
    val version: String,
) {
    /**
     * Visual similarity, 0..1.
     *
     * Hamming distance 0 → 1.0; the 64-bit space saturates fast, so similarity
     * decays linearly to 0 at [DISSIMILAR_AT] bits. Two unrelated screenshots
     * typically differ in ~30 of 64 bits, which lands near zero — as it should.
     */
    fun similarityTo(other: ImageEmbedding): Double {
        require(model == other.model && version == other.version) {
            "Incompatible image embeddings: $model v$version vs ${other.model} v${other.version}."
        }
        val distance = hammingDistance(hash, other.hash)
        return (1.0 - distance / DISSIMILAR_AT).coerceIn(0.0, 1.0)
    }

    companion object {
        /** At this Hamming distance two screenshots share nothing visual. */
        const val DISSIMILAR_AT = 24.0

        /**
         * At or below this distance two screenshots are near-duplicates:
         * same layout with small differences (a changed price, a new badge).
         * Shown as "Similar", never as "Duplicates" (§29).
         */
        const val NEAR_DUPLICATE_BITS = 8
    }
}

fun hammingDistance(a: Long, b: Long): Int = (a xor b).countOneBits()

/** Turns pixels into an [ImageEmbedding]. Works on raw RGB, no Bitmap type. */
interface ImageEmbeddingProvider {
    val model: String
    val version: String
    val isAvailable: Boolean

    /**
     * @param pixels row-major `0xRRGGBB` ints, alpha ignored.
     */
    fun embed(pixels: IntArray, width: Int, height: Int): ImageEmbedding
}

/**
 * Difference hash (dHash): downscale to 9×8 grayscale, record whether each
 * pixel is brighter than its right neighbour. 64 comparisons, 64 bits.
 *
 * Chosen over average-hash because gradients survive resizing and brightness
 * shifts better, and over pHash because it needs no DCT — a dozen lines of
 * integer arithmetic with no native dependency. The trade it makes: rotation
 * and cropping defeat it. Screenshots are never rotated, and cropping is what
 * the Hamming threshold absorbs.
 */
class DHashImageEmbeddingProvider : ImageEmbeddingProvider {

    override val model: String = MODEL_NAME
    override val version: String = MODEL_VERSION
    override val isAvailable: Boolean = true

    override fun embed(pixels: IntArray, width: Int, height: Int): ImageEmbedding {
        require(pixels.size == width * height) { "Pixel buffer does not match dimensions" }
        if (width <= 0 || height <= 0) return ImageEmbedding(0L, model, version)
        val gray = IntArray(width * height) { i ->
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Integer luminance: exact, no float drift between devices.
            (r * 77 + g * 150 + b * 29) shr 8
        }
        val small = PerceptualHash.downscale(gray, width, height, HASH_WIDTH + 1, HASH_HEIGHT)
        var hash = 0L
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH) {
                hash = hash shl 1
                if (small[y * (HASH_WIDTH + 1) + x] > small[y * (HASH_WIDTH + 1) + x + 1]) {
                    hash = hash or 1L
                }
            }
        }
        return ImageEmbedding(hash, model, version)
    }

    companion object {
        const val MODEL_NAME = "dhash"
        const val MODEL_VERSION = "1"
        private const val HASH_WIDTH = 8
        private const val HASH_HEIGHT = 8
    }
}

/** Box-sampled downscaling shared by the hash and color analysis. */
object PerceptualHash {

    /**
     * Averages each destination cell over its source box. Box sampling rather
     * than nearest-neighbour, so a one-pixel line still influences its cell —
     * screenshots are mostly thin lines on flat backgrounds.
     */
    fun downscale(gray: IntArray, width: Int, height: Int, targetW: Int, targetH: Int): IntArray {
        val out = IntArray(targetW * targetH)
        for (ty in 0 until targetH) {
            val y0 = (ty * height) / targetH
            val y1 = ((ty + 1) * height) / targetH
            for (tx in 0 until targetW) {
                val x0 = (tx * width) / targetW
                val x1 = ((tx + 1) * width) / targetW
                var sum = 0L
                var count = 0
                for (y in y0 until y1.coerceAtLeast(y0 + 1)) {
                    for (x in x0 until x1.coerceAtLeast(x0 + 1)) {
                        sum += gray[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
                        count++
                    }
                }
                out[ty * targetW + tx] = (sum / count.coerceAtLeast(1)).toInt()
            }
        }
        return out
    }
}
