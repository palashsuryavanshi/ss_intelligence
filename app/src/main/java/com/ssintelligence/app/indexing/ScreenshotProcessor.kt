package com.ssintelligence.app.indexing

import android.net.Uri
import com.ssintelligence.app.domain.model.OcrLevel
import com.ssintelligence.app.domain.model.ProcessingResult
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.duplicate.ImageSimilarityDetector
import com.ssintelligence.app.ml.extract.MetadataExtractor
import com.ssintelligence.app.ml.ocr.OcrException
import com.ssintelligence.app.ml.ocr.TextRecognizer
import com.ssintelligence.app.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * The central screenshot processing pipeline (§20):
 *
 * ```
 * Validate → Hash → Duplicate check → OCR → Extract URLs/Dates/Phones/
 * Prices/OTPs → Persist → COMPLETED
 * ```
 *
 * Failure policy: a failure in any single extraction step must not fail the
 * whole screenshot. Only a failure to read/decode the image is fatal, because
 * without an image there is nothing to index. Visual analysis is an extraction
 * step like any other: when it fails, the screenshot still indexes.
 */
class ScreenshotProcessor(
    private val repository: ScreenshotRepository,
    private val recognizer: TextRecognizer,
    private val similarityDetector: ImageSimilarityDetector,
    private val metadataExtractor: MetadataExtractor,
    private val processingDispatcher: CoroutineDispatcher,
    /**
     * Visual analysis. Null in tests that only exercise the text path;
     * wired in production through the service locator.
     */
    private val visualAnalyzer: com.ssintelligence.app.vision.BitmapVisualAnalyzer? = null,
) {

    /** Runs the full pipeline for one screenshot. Returns true when indexed. */
    suspend fun process(screenshot: Screenshot): Boolean = withContext(processingDispatcher) {
        val uri = runCatching { Uri.parse(screenshot.uri) }.getOrNull()
        if (uri == null) {
            repository.markFailed(screenshot.id, "Invalid stored URI")
            return@withContext false
        }

        repository.markProcessing(screenshot.id)

        // ---- 1. Content hash (§18) -----------------------------------------
        val contentHash = try {
            similarityDetector.fingerprint(uri)
        } catch (e: FileNotFoundException) {
            // The media was deleted or is inaccessible: record the reason and
            // stop retrying this row (§30).
            AppLog.w(TAG, "Media unavailable for screenshotId=${screenshot.id}")
            repository.markFailed(screenshot.id, "Screenshot is no longer available")
            return@withContext false
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Media access revoked for screenshotId=${screenshot.id}")
            repository.markFailed(screenshot.id, "Media access revoked")
            return@withContext false
        } catch (e: Throwable) {
            AppLog.e(TAG, "Hashing failed for screenshotId=${screenshot.id}", e)
            repository.markFailed(screenshot.id, "Unable to read image data")
            return@withContext false
        }

        // ---- 2. Exact duplicate check (§18) --------------------------------
        val duplicateOf = runCatching {
            repository.findCompletedByHash(contentHash, screenshot.id)?.id
        }.getOrNull()

        if (duplicateOf != null) {
            // Identical content is already indexed and searchable, so reuse its
            // OCR results instead of paying for OCR a second time.
            val canonical = repository.getById(duplicateOf)
            if (canonical != null && canonical.ocrText.isNotEmpty()) {
                val extracted = metadataExtractor.extract(canonical.ocrText)
                repository.saveResult(
                    ProcessingResult(
                        screenshotId = screenshot.id,
                        ocrText = canonical.ocrText,
                        blocks = emptyList(), // geometry belongs to the canonical row
                        urls = extracted.urls,
                        dates = extracted.dates,
                        phones = extracted.phones,
                        prices = extracted.prices,
                        otps = extracted.otps,
                        contentHash = contentHash,
                        duplicateOfId = duplicateOf,
                    )
                )
                AppLog.i(TAG, "Exact duplicate of screenshotId=$duplicateOf (id=${screenshot.id})")
                return@withContext true
            }
        }

        // ---- 3. OCR (§11) ---------------------------------------------------
        val ocr = try {
            recognizer.recognize(uri)
        } catch (e: OcrException) {
            AppLog.w(TAG, "OCR failed for screenshotId=${screenshot.id}: ${e.message}")
            repository.markFailed(screenshot.id, e.message ?: "Could not read text from this image")
            return@withContext false
        } catch (e: OutOfMemoryError) {
            AppLog.w(TAG, "OCR ran out of memory for screenshotId=${screenshot.id}")
            repository.markFailed(screenshot.id, "Not enough memory to process this image")
            return@withContext false
        } catch (e: Throwable) {
            // Deliberately Throwable: the OCR pipeline can surface linkage or
            // vendor errors that are Errors rather than Exceptions, and one of
            // them must not abort the batch.
            AppLog.e(TAG, "Unexpected OCR error for screenshotId=${screenshot.id}", e)
            repository.markFailed(screenshot.id, "Could not process this image")
            return@withContext false
        }

        // ---- 4. Metadata extraction (§13–§17) -------------------------------
        val text = ocr.text
        val extracted = metadataExtractor.extract(text)
        if (extracted.failures.isNotEmpty()) {
            // Non-fatal by design: partial extraction still indexes the
            // screenshot, and the log records which stage degraded.
            AppLog.w(
                TAG,
                "Partial extraction for screenshotId=${screenshot.id} stages=${extracted.failures}",
            )
        }

        // Only line-level geometry is retained: block rows duplicate line text
        // and roughly double the table size for no additional value (§12).
        val lineBlocks = ocr.blocks.filter { it.level == OcrLevel.LINE }

        // ---- 4b. Visual analysis (§4) ---------------------------------------
        // Non-fatal by design: a downscaled decode, a hash, a palette and a
        // type guess. When it fails the screenshot still indexes with its text.
        val visual = visualAnalyzer?.let { analyzer ->
            runCatching {
                analyzer.analyze(uri, lineBlocks, screenshot.width, screenshot.height)
            }.onFailure {
                AppLog.w(TAG, "Visual analysis failed for screenshotId=${screenshot.id}")
            }.getOrNull()
        }

        // ---- 5. Persist (§20) ----------------------------------------------
        try {
            repository.saveResult(
                ProcessingResult(
                    screenshotId = screenshot.id,
                    ocrText = text,
                    blocks = lineBlocks,
                    urls = extracted.urls,
                    dates = extracted.dates,
                    phones = extracted.phones,
                    prices = extracted.prices,
                    otps = extracted.otps,
                    contentHash = contentHash,
                    duplicateOfId = duplicateOf,
                    visual = visual,
                )
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to persist screenshotId=${screenshot.id}", e)
            repository.markFailed(screenshot.id, "Could not save extracted information")
            return@withContext false
        }

        AppLog.d(
            TAG,
            "Indexed screenshotId=${screenshot.id} characters=${text.length} " +
                "urls=${extracted.urls.size} prices=${extracted.prices.size} otps=${extracted.otps.size}",
        )
        true
    }

    private companion object {
        const val TAG = "ScreenshotProcessor"
    }
}

/** Status transitions the indexer relies on. */
internal fun ProcessingStatus.isTerminal(): Boolean =
    this == ProcessingStatus.COMPLETED || this == ProcessingStatus.FAILED
