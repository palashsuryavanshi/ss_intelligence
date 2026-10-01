package com.ssintelligence.app.vision

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.ssintelligence.app.domain.model.OcrBlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Visual analysis over a downscaled decode (§4, §39, §41).
 *
 * Never runs on the UI thread, never holds a full-resolution bitmap: the
 * decode targets ~192px on the long edge, computes everything from that, and
 * recycles immediately. A 1080×2400 screenshot decodes to roughly 88×192 —
 * about 17k pixels — which is all a palette, a hash and a brightness reading
 * need. The [VisualAnalyzer] is constructed once per process, never per
 * composable, and holds no model and no cache.
 */
class BitmapVisualAnalyzer(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
    private val imageEmbeddings: ImageEmbeddingProvider = DHashImageEmbeddingProvider(),
) {

    suspend fun analyze(uri: Uri, blocks: List<OcrBlock>, width: Int, height: Int): VisualAnalysis =
        withContext(ioDispatcher) {
            val pixels = decodeSmall(uri) ?: return@withContext emptyAnalysis(width, height)
            try {
                analyzePixels(pixels.pixels, pixels.width, pixels.height, blocks, width, height)
            } finally {
                // No Bitmap is retained: getPixels copies out, decode is local.
            }
        }

    fun embed(uri: Uri): ImageEmbedding? {
        val pixels = decodeSmall(uri) ?: return null
        return runCatching {
            imageEmbeddings.embed(pixels.pixels, pixels.width, pixels.height)
        }.getOrNull()
    }

    internal fun analyzePixels(
        pixels: IntArray,
        pixelsWidth: Int,
        pixelsHeight: Int,
        blocks: List<OcrBlock>,
        width: Int,
        height: Int,
    ): VisualAnalysis {
        val hash = runCatching {
            imageEmbeddings.embed(pixels, pixelsWidth, pixelsHeight).hash
        }.getOrElse { 0L }
        val palette = ColorAnalysis.analyze(pixels)
        val coverage = textCoverage(blocks)
        val layout = LayoutDetector.detect(blocks.map { it.toNormRect() })
        val typeInput = ScreenshotTypeClassifier.Input(
            ocrText = blocks.joinToString("\n") { it.text },
            hosts = emptyList(),
            hasPrice = false,
            hasOtp = false,
            hasPhone = false,
            width = width,
            height = height,
            isDark = palette.isDark,
            textCoverage = coverage,
            layout = layout,
        )
        // Hosts, prices and OTPs refine the type later at the repository layer,
        // which has the extraction tables; the pixel pass records the
        // geometry-only answer.
        return VisualAnalysis(
            imageHash = hash,
            colors = palette.colors.map { it.label },
            brightness = palette.brightness,
            isDark = palette.isDark,
            textCoverage = coverage,
            type = ScreenshotTypeClassifier.classify(typeInput).type,
            layout = layout,
            isLongScreenshot = width > 0 && height >= (width * VisualAnalysis.LONG_ASPECT).toInt(),
            modelVersion = VisualAnalysis.MODEL_VERSION,
        )
    }

    /**
     * Share of the image covered by OCR line boxes.
     *
     * Boxes are normalized 0..1000, so coverage is resolution-independent.
     * Overlapping boxes double-count; screenshots rarely overlap lines, and a
     * small overestimate is harmless for a threshold signal.
     */
    internal fun textCoverage(blocks: List<OcrBlock>): Double {
        if (blocks.isEmpty()) return 0.0
        var area = 0L
        for (block in blocks) {
            val w = (block.right - block.left).coerceAtLeast(0)
            val h = (block.bottom - block.top).coerceAtLeast(0)
            area += w.toLong() * h
        }
        return (area / 1_000_000.0).coerceIn(0.0, 1.0)
    }

    private fun OcrBlock.toNormRect() = NormRect(
        left = left.coerceIn(0, 1000),
        top = top.coerceIn(0, 1000),
        right = right.coerceIn(0, 1000),
        bottom = bottom.coerceIn(0, 1000),
    )

    private data class SmallPixels(val pixels: IntArray, val width: Int, val height: Int)

    private fun decodeSmall(uri: Uri): SmallPixels? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val fullW = bounds.outWidth
            val fullH = bounds.outHeight
            if (fullW <= 0 || fullH <= 0) return null
            var sample = 1
            while (fullW / sample > TARGET_LONG_EDGE && fullH / sample > TARGET_LONG_EDGE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w <= 0 || h <= 0) return null
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                SmallPixels(pixels, w, h)
            } finally {
                bitmap.recycle()
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun emptyAnalysis(width: Int, height: Int): VisualAnalysis = VisualAnalysis(
        imageHash = 0L,
        colors = emptyList(),
        brightness = 0.0,
        isDark = false,
        textCoverage = 0.0,
        type = if (width > 0 && height >= (width * VisualAnalysis.LONG_ASPECT).toInt()) {
            ScreenshotType.LONG_SCREENSHOT
        } else {
            ScreenshotType.OTHER
        },
        layout = ScreenLayout.NONE,
        isLongScreenshot = width > 0 && height >= (width * VisualAnalysis.LONG_ASPECT).toInt(),
        modelVersion = VisualAnalysis.MODEL_VERSION,
    )

    companion object {
        /** Long edge of the analysis decode. Small by design (§41). */
        const val TARGET_LONG_EDGE = 192
    }
}
