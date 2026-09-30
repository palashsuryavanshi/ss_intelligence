package com.ssintelligence.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local screenshot index (§9, §10).
 *
 * Design notes:
 * - Image bytes are never stored; the MediaStore URI is the only image
 *   reference, so the database stays small and the originals stay under the
 *   user's control.
 * - Extracted information lives in normalized tables so Phase 2+ can filter
 *   (`currency = 'INR' AND amount >= 30000`) and Phase 4 can join embeddings
 *   without a schema rewrite.
 * - Full-text search uses an external-content FTS4 table, so OCR text is not
 *   duplicated on disk and the index cannot drift out of sync.
 */
@Database(
    entities = [
        ScreenshotEntity::class,
        ScreenshotFtsEntity::class,
        ExtractedUrlEntity::class,
        ExtractedDateEntity::class,
        ExtractedPhoneEntity::class,
        ExtractedPriceEntity::class,
        ExtractedOtpEntity::class,
        OcrBlockEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SsIntelligenceDatabase : RoomDatabase() {

    abstract fun screenshotDao(): ScreenshotDao

    companion object {
        const val NAME = "ss_intelligence.db"

        fun build(context: Context): SsIntelligenceDatabase =
            Room.databaseBuilder(context.applicationContext, SsIntelligenceDatabase::class.java, NAME)
                // No destructive fallback: losing an index silently would be
                // worse than a visible error. "Clear Index" in Settings is the
                // explicit recovery path.
                .build()
    }
}
