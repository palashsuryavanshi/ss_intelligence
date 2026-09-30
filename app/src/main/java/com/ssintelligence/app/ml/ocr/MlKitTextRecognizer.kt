package com.ssintelligence.app.ml.ocr

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ssintelligence.app.domain.model.OcrBlock
import com.ssintelligence.app.domain.model.OcrLevel
import com.ssintelligence.app.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit on-device text recognition (§11).
 *
 * Pipeline: MediaStore URI → [InputImage.fromFilePath] → ML Kit → structured
 * text. The bitmap is never created here: passing the file path lets ML Kit
 * decode and downscale internally, which avoids holding a multi-megabyte
 * bitmap for long (screenshot-sized) images.
 *
 * Bitmaps are only used by [decodeBounds] for pre-flight validation.
 */
class MlKitTextRecognizer(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
) : TextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(uri: Uri): OcrResult = withContext(ioDispatcher) {
        val bounds = decodeBounds(uri) ?: throw OcrException("Unsupported or corrupt image")

        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: IOException) {
            throw OcrException("Unable to read image bytes", e)
        }

        val visionText = try {
            suspendCancellableCoroutine<Text> { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(OcrException("OCR failed", it)) }
                    .addOnCanceledListener { continuation.cancel() }
            }
        } catch (e: OcrException) {
            throw e
        } catch (e: Throwable) {
            throw OcrException("OCR failed", e)
        }

        val (imageWidth, imageHeight) = bounds
        if (visionText.text.isBlank() && imageWidth > 0) {
            AppLog.d(TAG, "OCR produced no text for image ${imageWidth}x$imageHeight")
        }

        val scaleX = if (imageWidth > 0) 1000f / imageWidth else 1f
        val scaleY = if (imageHeight > 0) 1000f / imageHeight else 1f
        val blocks = buildList {
            for (block in visionText.textBlocks) {
                val blockBox = block.boundingBox
                add(
                    OcrBlock(
                        level = OcrLevel.BLOCK,
                        text = block.text,
                        left = normalize(blockBox?.left, scaleX),
                        top = normalize(blockBox?.top, scaleY),
                        right = normalize(blockBox?.right, scaleX),
                        bottom = normalize(blockBox?.bottom, scaleY),
                        // ML Kit Latin does not expose per-block confidence; null
                        // is stored rather than inventing a value.
                        confidence = null,
                    )
                )
                for (line in block.lines) {
                    val lineBox = line.boundingBox
                    add(
                        OcrBlock(
                            level = OcrLevel.LINE,
                            text = line.text,
                            left = normalize(lineBox?.left, scaleX),
                            top = normalize(lineBox?.top, scaleY),
                            right = normalize(lineBox?.right, scaleX),
                            bottom = normalize(lineBox?.bottom, scaleY),
                            confidence = null,
                        )
                    )
                }
            }
        }
        OcrResult(text = visionText.text, blocks = blocks)
    }

    /** Converts a pixel coordinate into the normalized 0..1000 space. */
    private fun normalize(value: Int?, scale: Float): Int =
        ((value ?: 0) * scale).toInt().coerceIn(0, 1000)

    /** Reads dimensions only (inJustDecodeBounds) to validate decodability. */
    private fun decodeBounds(uri: Uri): Pair<Int, Int>? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    } catch (e: FileNotFoundException) {
        throw OcrException("Screenshot is no longer accessible", e)
    } catch (e: SecurityException) {
        throw OcrException("Read access revoked for screenshot", e)
    } catch (e: OutOfMemoryError) {
        throw OcrException("Image too large to decode", null)
    } catch (e: Throwable) {
        // Throwable, not Exception: a vendor OCR failure can surface as an
        // Error, and it must still be reported as a per-row failure.
        throw OcrException("Unable to decode image", e)
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    private companion object {
        const val TAG = "OCRProcessor"
    }
}
