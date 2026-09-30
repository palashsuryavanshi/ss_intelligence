package com.ssintelligence.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        SearchHistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SsIntelligenceDatabase : RoomDatabase() {

    abstract fun screenshotDao(): ScreenshotDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        const val NAME = "ss_intelligence.db"

        /**
         * v1 → v2 adds the opt-in search history table.
         *
         * Written as an explicit migration rather than a destructive fallback:
         * silently dropping a user's index because a dependency bumped a
         * version is exactly the failure mode "Clear Index" exists to avoid.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `query` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_history_query` " +
                        "ON `search_history` (`query`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_search_history_created_at` " +
                        "ON `search_history` (`created_at`)",
                )
            }
        }

        fun build(context: Context): SsIntelligenceDatabase =
            Room.databaseBuilder(context.applicationContext, SsIntelligenceDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                // No destructive fallback: losing an index silently would be
                // worse than a visible error. "Clear Index" in Settings is the
                // explicit recovery path.
                .build()
    }
}
