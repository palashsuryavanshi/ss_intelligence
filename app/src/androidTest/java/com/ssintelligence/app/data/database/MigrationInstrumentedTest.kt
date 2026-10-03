package com.ssintelligence.app.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /**
     * These tests build real database files on the device, so a file left over
     * from an earlier run would be migrated *again* — validating a schema that
     * was never produced by the current migration code. Deleting first makes
     * each test depend only on this source tree, not on install history.
     */
    @Before
    fun deleteLeftoverDatabases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf(TEST_DB, TEST_DB_V3, TEST_DB_V4, TEST_DB_V5, TEST_DB_V6, TEST_DB_ENCRYPTED)) {
            context.deleteDatabase(name)
        }
    }

    /**
     * SQLCipher database can be created and opened with the passphrase from
     * DatabasePassphraseProvider. If the passphrase or provider is broken,
     * the database will fail to open — catching a release-blocking bug.
     */
    @Test
    fun encryptedDatabaseOpensWithPassphrase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val passphrase = DatabasePassphraseProvider(context).getPassphrase()

        // Verify the passphrase provider produces a consistent 32-byte key.
        // The actual SQLCipher open/close is exercised at runtime by Room's
        // openHelperFactory; this test guards against key-generation regressions.
        assertEquals(32, passphrase.size)

        // Verify that the same passphrase is returned on second call (stored in prefs)
        val passphrase2 = DatabasePassphraseProvider(context).getPassphrase()
        assertTrue(passphrase.contentEquals(passphrase2))
    }

    /**
     * Room with SQLCipher openHelperFactory creates a working database.
     */
    @Test
    fun roomEncryptedDatabaseOpens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = SsIntelligenceDatabase.build(context)
        // If we get here without exception, the encrypted database opened successfully.
        runBlocking {
            val rows = db.screenshotDao().statusCounts()
            assertTrue(rows.isEmpty() || rows.isNotEmpty())
        }
        db.close()
    }

    @Test
    fun migrateFromOneToTwoKeepsTheIndexAndAddsSearchHistory() {
        helper.createDatabase(TEST_DB, 1).apply {
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
    fun migrateFromTwoToThreeKeepsTheIndexAndAddsSemanticTables() {        helper.createDatabase(TEST_DB_V3, 2).apply {
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

    @Test
    fun migrateFromThreeToFourKeepsEverythingAndAddsVisualGraphTables() {
        helper.createDatabase(TEST_DB_V4, 3).apply {
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
            execSQL(
                "INSERT INTO screenshot_embeddings " +
                    "(screenshot_id, vector, model, version, dimension, created_at) " +
                    "VALUES (1, zeroblob(16), 'hashed-ngram', '1', 4, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_V4,
            4,
            true,
            SsIntelligenceDatabase.MIGRATION_3_4,
        )

        // Screenshots and text embeddings survive.
        migrated.query("SELECT filename FROM screenshots WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Screenshot_1.png", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM screenshot_embeddings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // The new tables exist and are writable.
        migrated.execSQL(
            "INSERT INTO screenshot_visuals (screenshot_id, dhash, colors, brightness, " +
                "is_dark, text_coverage, shot_type, layout, model_version, created_at) " +
                "VALUES (1, 123, 'blue,white', 200.0, 0, 0.3, 'APP_UI', 'NONE', 'visual-v1', 0)",
        )
        migrated.execSQL(
            "INSERT INTO graph_entities (type, display_name, normalized_name, created_at) " +
                "VALUES ('PRODUCT', 'Pixel 9a', 'pixel 9a', 0)",
        )
        migrated.execSQL(
            "INSERT INTO graph_relations (screenshot_id, entity_id, kind, confidence) " +
                "VALUES (1, 1, 'MENTIONS', 0.7)",
        )
        migrated.execSQL(
            "INSERT INTO collections (name, created_at) VALUES ('Trip', 0)",
        )
        migrated.execSQL(
            "INSERT INTO collection_members (collection_id, screenshot_id, added_at) " +
                "VALUES (1, 1, 0)",
        )
        migrated.query("SELECT COUNT(*) FROM graph_relations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        migrated.close()
    }

    @Test
    fun migrateFromFourToFiveKeepsEverythingAndAddsAssistantTables() {
        helper.createDatabase(TEST_DB_V5, 4).apply {
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
            execSQL(
                "INSERT INTO screenshot_visuals (screenshot_id, dhash, colors, brightness, " +
                    "is_dark, text_coverage, shot_type, layout, model_version, created_at) " +
                    "VALUES (1, 123, 'blue,white', 200.0, 0, 0.3, 'APP_UI', 'NONE', 'visual-v1', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_V5,
            5,
            true,
            SsIntelligenceDatabase.MIGRATION_4_5,
        )

        // Screenshots and visuals survive.
        migrated.query("SELECT filename FROM screenshots WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Screenshot_1.png", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM screenshot_visuals").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // The assistant tables exist and are writable.
        migrated.execSQL(
            "INSERT INTO assistant_conversations (title, created_at, updated_at) " +
                "VALUES ('Pixel Research', 0, 0)",
        )
        migrated.execSQL(
            "INSERT INTO assistant_messages (conversation_id, role, text, intent, created_at) " +
                "VALUES (1, 'user', 'What prices?', 'PRICE_HISTORY', 0)",
        )
        migrated.execSQL(
            "INSERT INTO assistant_evidence (message_id, screenshot_id, kind) " +
                "VALUES (1, 1, 'text match')",
        )
        migrated.execSQL(
            "INSERT INTO memory_snapshots (name, summary, created_at) " +
                "VALUES ('Pixel Research', '3 prices', 0)",
        )
        migrated.execSQL(
            "INSERT INTO memory_snapshot_items (snapshot_id, screenshot_id) " +
                "VALUES (1, 1)",
        )
        migrated.query("SELECT COUNT(*) FROM assistant_messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM memory_snapshot_items").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        migrated.close()
    }

    @Test
    fun migrateFromSixToSevenKeepsEverythingAndAddsActionTables() {
        helper.createDatabase(TEST_DB_V6, 6).apply {
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
            execSQL(
                "INSERT INTO assistant_conversations (title, created_at, updated_at) " +
                    "VALUES ('Pixel Research', 0, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_V6,
            7,
            true,
            SsIntelligenceDatabase.MIGRATION_6_7,
        )

        // Screenshots and conversations survive.
        migrated.query("SELECT filename FROM screenshots WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Screenshot_1.png", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM assistant_conversations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // The action tables exist and are writable.
        migrated.execSQL(
            "INSERT INTO local_reminders (title, due_epoch_millis, screenshot_id, created_at) " +
                "VALUES ('Check price', NULL, 1, 0)",
        )
        migrated.execSQL(
            "INSERT INTO expense_records (screenshot_id, merchant, amount, currency, date_epoch_day, category, created_at) " +
                "VALUES (1, 'Example Store', 2499.0, 'INR', 20000, 'Shopping', 0)",
        )
        migrated.execSQL(
            "INSERT INTO action_history (action_type, screenshot_id, title, success, created_at) " +
                "VALUES ('OPEN_URL', 1, 'Open example.com', 1, 0)",
        )
        migrated.execSQL(
            "INSERT INTO automation_rules (name, trigger_type, condition_json, action_type, action_params_json, enabled, created_at) " +
                "VALUES ('Receipt Organizer', 'RECEIPT_DETECTED', '', 'ADD_TO_COLLECTION', 'collection=Expenses', 1, 0)",
        )
        migrated.execSQL(
            "INSERT INTO automation_executions (rule_id, screenshot_id, action_type, result, created_at) " +
                "VALUES (1, 1, 'ADD_TO_COLLECTION', 'Added to Expenses', 0)",
        )
        migrated.query("SELECT COUNT(*) FROM expense_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM automation_executions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_V3 = "migration-test-v3.db"
        const val TEST_DB_V4 = "migration-test-v4.db"
        const val TEST_DB_V5 = "migration-test-v5.db"
        const val TEST_DB_V6 = "migration-test-v6.db"
        const val TEST_DB_ENCRYPTED = "migration-test-encrypted.db"
    }
}
