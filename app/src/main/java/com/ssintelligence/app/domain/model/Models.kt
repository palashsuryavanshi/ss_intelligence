package com.ssintelligence.app.domain.model

/** Indexing lifecycle of a single screenshot. Stored as [Screenshot.status]. */
enum class ProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}

/** Search result filters (§26). Each maps to an extracted-info table. */
enum class SearchFilter {
    ALL,
    URLS,
    PRICES,
    DATES,
    PHONES,
    OTPS,
    DUPLICATES,
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Which images the scanner should index. */
enum class IndexingScope { SCREENSHOTS_ONLY, ALL_IMAGES }

data class Screenshot(
    val id: Long,
    val mediaStoreId: Long,
    val uri: String,
    val filename: String,
    val relativePath: String?,
    /** Epoch seconds, as reported by MediaStore. */
    val dateAdded: Long,
    val dateModified: Long,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val ocrText: String,
    val contentHash: String?,
    /** Row id of the canonical screenshot when this row is an exact duplicate. */
    val duplicateOfId: Long?,
    val status: ProcessingStatus,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isDuplicate: Boolean get() = duplicateOfId != null
}

data class ExtractedUrl(val id: Long, val screenshotId: Long, val url: String, val host: String)

data class ExtractedDate(
    val id: Long,
    val screenshotId: Long,
    val rawText: String,
    /** Days since epoch (see [java.time.LocalDate.toEpochDay]). */
    val epochDay: Long,
    /** False when the OCR text had no year and the current year was assumed. */
    val hasYear: Boolean,
)

data class ExtractedPhone(
    val id: Long,
    val screenshotId: Long,
    val rawText: String,
    /** Digits with leading '+', e.g. "+919876543210". */
    val normalized: String,
    val country: String,
)

data class ExtractedPrice(
    val id: Long,
    val screenshotId: Long,
    val rawText: String,
    /** ISO-4217 code, e.g. "INR". */
    val currency: String,
    val amount: Double,
)

data class ExtractedOtp(val id: Long, val screenshotId: Long, val code: String)

/** Full detail view for one screenshot. */
data class ScreenshotDetail(
    val screenshot: Screenshot,
    val urls: List<ExtractedUrl>,
    val dates: List<ExtractedDate>,
    val phones: List<ExtractedPhone>,
    val prices: List<ExtractedPrice>,
    val otps: List<ExtractedOtp>,
)

/** Aggregate counts shown on the home screen. */
data class IndexingStats(
    val total: Int,
    val completed: Int,
    val pending: Int,
    val processing: Int,
    val failed: Int,
    val duplicateItems: Int,
    val duplicateGroups: Int,
)

/** Point-in-time progress snapshot for an indexing run. */
data class IndexingProgress(val total: Int, val processed: Int, val failed: Int)

/** One row discovered via MediaStore (not yet necessarily indexed). */
data class MediaImage(
    val mediaStoreId: Long,
    /** Content URI string, e.g. content://media/external/images/media/12345 */
    val uri: String,
    val filename: String,
    val relativePath: String?,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val isLikelyScreenshot: Boolean,
)

/** Outcome of reconciling a MediaStore scan against the index. */
data class DiscoverResult(val added: Int, val updated: Int, val removed: Int)

/** A group of screenshots sharing identical image content. */
data class DuplicateGroup(
    val contentHash: String,
    val screenshots: List<Screenshot>,
)

/** Everything the pipeline produces for one screenshot. */
data class ProcessingResult(
    val screenshotId: Long,
    val ocrText: String,
    val blocks: List<OcrBlock>,
    val urls: List<UrlCandidate>,
    val dates: List<DateCandidate>,
    val phones: List<PhoneCandidate>,
    val prices: List<PriceCandidate>,
    val otps: List<OtpCandidate>,
    val contentHash: String,
    /** Non-null when this image exactly matches an already-indexed screenshot. */
    val duplicateOfId: Long?,
)

/** Structured OCR output preserved for future visual search (§12). */
data class OcrBlock(
    val level: OcrLevel,
    val text: String,
    /** Normalized 0..1000 bounding box. */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float?,
)

enum class OcrLevel { BLOCK, LINE }

data class UrlCandidate(val url: String, val host: String)
data class DateCandidate(val rawText: String, val epochDay: Long, val hasYear: Boolean)
data class PhoneCandidate(val rawText: String, val normalized: String, val country: String)
data class PriceCandidate(val rawText: String, val currency: String, val amount: Double)
data class OtpCandidate(val code: String)
