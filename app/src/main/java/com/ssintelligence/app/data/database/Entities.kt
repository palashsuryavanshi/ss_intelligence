package com.ssintelligence.app.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Core screenshot row (§9).
 *
 * No image binary is stored — only the MediaStore [uri], which is re-opened
 * on demand. Processing state is tracked in [status] so an interrupted run can
 * resume without re-doing finished work.
 */
@Entity(
    tableName = "screenshots",
    indices = [
        // Incremental indexing compares discovered media against this (§8).
        Index(value = ["media_store_id"], unique = true),
        // Exact-duplicate grouping (§18).
        Index(value = ["content_hash"]),
        // Composite index powers keyset pagination of the browser (§31).
        Index(value = ["date_added", "id"]),
        Index(value = ["date_modified"]),
        // Worker queries pending work by status.
        Index(value = ["status"]),
        Index(value = ["duplicate_of_id"]),
    ],
)
data class ScreenshotEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,

    /** `content://media/external/images/media/12345` (§6). */
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "filename")
    val filename: String,

    @ColumnInfo(name = "relative_path")
    val relativePath: String?,

    /** Epoch seconds, matching MediaStore. */
    @ColumnInfo(name = "date_added")
    val dateAdded: Long,

    @ColumnInfo(name = "date_modified")
    val dateModified: Long,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "width")
    val width: Int,

    @ColumnInfo(name = "height")
    val height: Int,

    @ColumnInfo(name = "mime_type")
    val mimeType: String?,

    @ColumnInfo(name = "ocr_text")
    val ocrText: String = "",

    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,

    /** Canonical row id when this image is an exact duplicate of another. */
    @ColumnInfo(name = "duplicate_of_id")
    val duplicateOfId: Long? = null,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "processing_error")
    val processingError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * Full-text search index over OCR content and filename (§25).
 *
 * `contentEntity` makes this an *external content* FTS table: SQLite stores
 * only the inverted index, not a second copy of the OCR text. Combined with
 * the triggers created in [SsIntelligenceDatabase], the index stays in sync
 * automatically and does not double database size.
 */
@Fts4(contentEntity = ScreenshotEntity::class)
@Entity(tableName = "screenshots_fts")
data class ScreenshotFtsEntity(
    @ColumnInfo(name = "filename")
    val filename: String,

    @ColumnInfo(name = "ocr_text")
    val ocrText: String,
)

@Entity(
    tableName = "extracted_urls",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["screenshot_id"]), Index(value = ["host"])],
)
data class ExtractedUrlEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    /** Normalized absolute URL. */
    @ColumnInfo(name = "url") val url: String,
    /** Lowercase host, extracted once so future filters do not re-parse. */
    @ColumnInfo(name = "host") val host: String,
)

@Entity(
    tableName = "extracted_dates",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["screenshot_id"]), Index(value = ["epoch_day"])],
)
data class ExtractedDateEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    /** Exactly what OCR produced, kept for auditing ambiguous matches (§16). */
    @ColumnInfo(name = "raw_text") val rawText: String,
    /** Days since 1970-01-01 (`LocalDate.toEpochDay`). */
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    /** 0 when the year was inferred, 1 when the OCR text carried a year. */
    @ColumnInfo(name = "has_year") val hasYear: Boolean,
)

@Entity(
    tableName = "extracted_phones",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["screenshot_id"]), Index(value = ["normalized"])],
)
data class ExtractedPhoneEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "raw_text") val rawText: String,
    /** Canonical form, e.g. "+919876543210". Single representation only (§14). */
    @ColumnInfo(name = "normalized") val normalized: String,
    @ColumnInfo(name = "country") val country: String,
)

@Entity(
    tableName = "extracted_prices",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["screenshot_id"]), Index(value = ["currency", "amount"])],
)
data class ExtractedPriceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "raw_text") val rawText: String,
    /** ISO-4217, e.g. "INR". Enables filters like `currency = 'INR' AND amount >= 30000`. */
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "amount") val amount: Double,
)

/**
 * OTP-like codes (§17).
 *
 * Deliberately excluded from the FTS index: these values are sensitive and
 * must not be discoverable by typing a code into the search box.
 */
@Entity(
    tableName = "extracted_otps",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["screenshot_id"])],
)
data class ExtractedOtpEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "code") val code: String,
)

/**
 * Structured OCR geometry (§12).
 *
 * Boxes are normalized to 0..1000 so the schema is resolution independent and
 * the table stays small (four small ints per line instead of pixel rects).
 */
@Entity(
    tableName = "ocr_blocks",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["screenshot_id"])],
)
data class OcrBlockEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    /** "BLOCK" or "LINE". */
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "box_left") val left: Int,
    @ColumnInfo(name = "box_top") val top: Int,
    @ColumnInfo(name = "box_right") val right: Int,
    @ColumnInfo(name = "box_bottom") val bottom: Int,
    /** Null when the engine does not report confidence. */
    @ColumnInfo(name = "confidence") val confidence: Float?,
)
