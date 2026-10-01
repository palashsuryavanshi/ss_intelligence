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
        ScreenshotEmbeddingEntity::class,
        ScreenshotCategoryEntity::class,
        ScreenshotVisualEntity::class,
        GraphEntityRow::class,
        GraphRelationRow::class,
        CollectionRow::class,
        CollectionMemberRow::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class SsIntelligenceDatabase : RoomDatabase() {

    abstract fun screenshotDao(): ScreenshotDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    abstract fun semanticDao(): SemanticDao

    abstract fun visualDao(): VisualDao

    abstract fun graphDao(): GraphDao

    abstract fun collectionDao(): CollectionDao

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

        /**
         * v2 → v3 adds the semantic tables: embeddings and categories.
         *
         * Both are derived data — they can always be rebuilt from OCR text —
         * but they are still migrated rather than dropped, because a silent
         * rebuild of thousands of embeddings on first launch after an upgrade
         * would be a battery and latency surprise.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `screenshot_embeddings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `screenshot_id` INTEGER NOT NULL,
                        `vector` BLOB NOT NULL,
                        `model` TEXT NOT NULL,
                        `version` TEXT NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        FOREIGN KEY(`screenshot_id`) REFERENCES `screenshots`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_screenshot_embeddings_screenshot_id` " +
                        "ON `screenshot_embeddings` (`screenshot_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `screenshot_categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `screenshot_id` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `classifier_version` TEXT NOT NULL,
                        FOREIGN KEY(`screenshot_id`) REFERENCES `screenshots`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_screenshot_categories_screenshot_id` " +
                        "ON `screenshot_categories` (`screenshot_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_screenshot_categories_category` " +
                        "ON `screenshot_categories` (`category`)",
                )
            }
        }

        /**
         * v3 → v4 adds visuals, the knowledge graph and collections.
         *
         * All derived data again: visuals recompute from pixels, the graph
         * rebuilds from extraction tables, collections are user data carried
         * over untouched (empty on upgrade by definition).
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `screenshot_visuals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `screenshot_id` INTEGER NOT NULL,
                        `dhash` INTEGER NOT NULL,
                        `colors` TEXT NOT NULL,
                        `brightness` REAL NOT NULL,
                        `is_dark` INTEGER NOT NULL,
                        `text_coverage` REAL NOT NULL,
                        `shot_type` TEXT NOT NULL,
                        `layout` TEXT NOT NULL,
                        `model_version` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        FOREIGN KEY(`screenshot_id`) REFERENCES `screenshots`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_screenshot_visuals_screenshot_id` " +
                        "ON `screenshot_visuals` (`screenshot_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `graph_entities` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `normalized_name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_graph_entities_type_normalized_name` " +
                        "ON `graph_entities` (`type`, `normalized_name`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `graph_relations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `screenshot_id` INTEGER NOT NULL,
                        `entity_id` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        FOREIGN KEY(`screenshot_id`) REFERENCES `screenshots`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`entity_id`) REFERENCES `graph_entities`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_graph_relations_screenshot_id` " +
                        "ON `graph_relations` (`screenshot_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_graph_relations_entity_id` " +
                        "ON `graph_relations` (`entity_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `collections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `collection_members` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `collection_id` INTEGER NOT NULL,
                        `screenshot_id` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        FOREIGN KEY(`collection_id`) REFERENCES `collections`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`screenshot_id`) REFERENCES `screenshots`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_collection_members_collection_id` " +
                        "ON `collection_members` (`collection_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_collection_members_screenshot_id` " +
                        "ON `collection_members` (`screenshot_id`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_collection_members_collection_id_screenshot_id` " +
                        "ON `collection_members` (`collection_id`, `screenshot_id`)",
                )
            }
        }

        fun build(context: Context): SsIntelligenceDatabase =
            Room.databaseBuilder(context.applicationContext, SsIntelligenceDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // No destructive fallback: losing an index silently would be
                // worse than a visible error. "Clear Index" in Settings is the
                // explicit recovery path.
                .build()
    }
}
