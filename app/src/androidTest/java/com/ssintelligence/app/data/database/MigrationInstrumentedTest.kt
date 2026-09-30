package com.ssintelligence.app.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema migrations (§9).
 *
 * Each migration must preserve the whole index: losing a user's screenshots
 * because a table was added would be a silent, permanent data loss and exactly
 * what "Clear Index" is meant to prevent.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SsIntelligenceDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateFromOneToTwoKeepsTheIndexAndAddsSearchHistory() {        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO screenshots (
                    id, media_store_id, uri, filename, relative_path, date_added,
                    date_modified, file_size, width, height, mime_type, ocr_text,
                    content_hash, duplicate_of_id, status, processing_error,
                    created_at, updated_at
                ) VALUES (1, 42, 'content://x', 'Screenshot_1.png', 'Pictures/Screenshots',
                    1700000000, 1700000000, 1024, 1080, 2400, 'image/png',
                    'Google Pixel 9a', 'hash-1', NULL, 'COMPLETED', NULL, 0, 0)
                """.trimIndent(),
            )
            close()
        }

        // `runMigrationsAndValidate` also checks the migrated schema against the
        // exported v2 JSON, so a hand-written CREATE TABLE that drifted from the
        // entity definition would fail here rather than at runtime.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            SsIntelligenceDatabase.MIGRATION_1_2,
        )

        migrated.query("SELECT filename, ocr_text FROM screenshots WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Screenshot_1.png", cursor.getString(0))
            assertEquals("Google Pixel 9a", cursor.getString(1))
        }

        // The FTS index still answers, i.e. the external-content triggers
        // survived the migration.
        migrated.query(
            "SELECT rowid FROM screenshots_fts WHERE screenshots_fts MATCH '\"pixel\"*'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }

        // The new table exists and is writable.
        migrated.execSQL("INSERT INTO search_history (query, created_at) VALUES ('pixel', 1)")
        migrated.query("SELECT COUNT(*) FROM search_history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        migrated.close()
    }

    @Test
    fun migrateFromTwoToThreeKeepsTheIndexAndAddsSemanticTables() {
        helper.createDatabase(TEST_DB_V3, 2).apply {
            execSQL(
                """
                INSERT INTO screenshots (
                    id, media_store_id, uri, filename, relative_path, date_added,
                    date_modified, file_size, width, height, mime_type, ocr_text,
                    content_hash, duplicate_of_id, status, processing_error,
                    created_at, updated_at
                ) VALUES (1, 42, 'content://x', 'Screenshot_1.png', 'Pictures/Screenshots',
                    1700000000, 1700000000, 1024, 1080, 2400, 'image/png',
                    'Google Pixel 9a', 'hash-1', NULL, 'COMPLETED', NULL, 0, 0)
                """.trimIndent(),
            )
            execSQL("INSERT INTO search_history (query, created_at) VALUES ('pixel', 1)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_V3,
            3,
            true,
            SsIntelligenceDatabase.MIGRATION_2_3,
        )

        // The index and the history survive.
        migrated.query("SELECT filename FROM screenshots WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Screenshot_1.png", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM search_history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // The semantic tables exist and are writable.
        migrated.execSQL(
            "INSERT INTO screenshot_embeddings " +
                "(screenshot_id, vector, model, version, dimension, created_at) " +
                "VALUES (1, zeroblob(16), 'm', '1', 4, 0)",
        )
        migrated.execSQL(
            "INSERT INTO screenshot_categories " +
                "(screenshot_id, category, confidence, source, classifier_version) " +
                "VALUES (1, 'SHOPPING', 0.8, 'auto', 'rules-v1')",
        )
        migrated.query("SELECT COUNT(*) FROM screenshot_embeddings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_V3 = "migration-test-v3.db"
    }
}
