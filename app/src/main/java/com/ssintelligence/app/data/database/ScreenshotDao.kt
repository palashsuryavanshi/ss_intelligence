package com.ssintelligence.app.data.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Row for a screenshot plus its relevance score from the search engine (§28). */
data class ScreenshotSearchRow(
    @Embedded val screenshot: ScreenshotEntity,
    val score: Int,
)

/** A content-hash group with more than one member (§18). */
data class DuplicateHashRow(
    @androidx.room.ColumnInfo(name = "content_hash") val contentHash: String,
    @androidx.room.ColumnInfo(name = "member_count") val memberCount: Int,
)

/** Aggregate counters for the home screen. */
data class StatusCount(
    @androidx.room.ColumnInfo(name = "status") val status: String,
    @androidx.room.ColumnInfo(name = "cnt") val count: Int,
)

@Dao
interface ScreenshotDao {

    // ---------------------------------------------------------------- reads

    /** Clears derived data and re-queues everything for a full rebuild (§34). */
    @Transaction
    suspend fun requeueAllForReprocessing(now: Long) {
        deleteOcrBlocksForAll()
        deleteExtractedUrlsForAll()
        deleteExtractedDatesForAll()
        deleteExtractedPhonesForAll()
        deleteExtractedPricesForAll()
        deleteOtpsForAll()
        requeueAllRows(now)
    }

    @Query("SELECT * FROM screenshots WHERE id = :id")
    suspend fun getById(id: Long): ScreenshotEntity?

    @Query("DELETE FROM ocr_blocks")
    suspend fun deleteOcrBlocksForAll()

    @Query("DELETE FROM extracted_urls")
    suspend fun deleteExtractedUrlsForAll()

    @Query("DELETE FROM extracted_dates")
    suspend fun deleteExtractedDatesForAll()

    @Query("DELETE FROM extracted_phones")
    suspend fun deleteExtractedPhonesForAll()

    @Query("DELETE FROM extracted_prices")
    suspend fun deleteExtractedPricesForAll()

    @Query("DELETE FROM extracted_otps")
    suspend fun deleteOtpsForAll()

    @Query(
        """
        UPDATE screenshots
        SET status = 'PENDING', ocr_text = '', content_hash = NULL,
            duplicate_of_id = NULL, processing_error = NULL, updated_at = :now
        """
    )
    suspend fun requeueAllRows(now: Long)

    @Query("SELECT * FROM screenshots WHERE media_store_id = :mediaStoreId")
    suspend fun getByMediaStoreId(mediaStoreId: Long): ScreenshotEntity?

    @Query("SELECT * FROM screenshots WHERE media_store_id IN (:mediaStoreIds)")
    suspend fun getByMediaStoreIds(mediaStoreIds: List<Long>): List<ScreenshotEntity>

    @Query("SELECT * FROM screenshots WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ScreenshotEntity>

    @Query("SELECT * FROM screenshots WHERE id = :id")
    fun observeById(id: Long): Flow<ScreenshotEntity?>

    @Query("SELECT * FROM screenshots ORDER BY date_added DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScreenshotEntity>>

    /**
     * Members of duplicate groups, newest group members first. Grouping is done
     * in the repository so the DAO stays free of presentation logic.
     */
    @Query(
        """
        SELECT s.* FROM screenshots s
        JOIN (
            SELECT content_hash FROM screenshots
            WHERE content_hash IS NOT NULL
            GROUP BY content_hash HAVING COUNT(*) > 1
        ) d ON d.content_hash = s.content_hash
        ORDER BY s.content_hash, s.date_added DESC, s.id DESC
        LIMIT :limit
        """
    )
    fun observeDuplicateMembers(limit: Int): Flow<List<ScreenshotEntity>>

    @Query("SELECT status, COUNT(*) AS cnt FROM screenshots GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT status, COUNT(*) AS cnt FROM screenshots GROUP BY status")
    suspend fun statusCounts(): List<StatusCount>

    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE duplicate_of_id IS NOT NULL
        """
    )
    fun observeDuplicateItemCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT content_hash FROM screenshots
            WHERE content_hash IS NOT NULL
            GROUP BY content_hash HAVING COUNT(*) > 1
        )
        """
    )
    fun observeDuplicateGroupCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots")
    fun observeTotalCount(): Flow<Int>

    // -------------------------------------------------------------- workers

    /**
     * Oldest-first pending work. PROCESSING rows are intentionally excluded:
     * a row left in PROCESSING by a killed process is re-queued by
     * [resetStaleProcessing] rather than being trusted as in-flight.
     */
    @Query(
        """
        SELECT * FROM screenshots
        WHERE status = 'PENDING'
        ORDER BY date_added ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun nextPending(limit: Int): List<ScreenshotEntity>

    @Query("SELECT COUNT(*) FROM screenshots WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM screenshots WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots WHERE status = 'PROCESSING'")
    fun observeProcessingCount(): Flow<Int>

    @Query(
        """
        UPDATE screenshots SET status = 'PENDING', updated_at = :now
        WHERE status = 'PROCESSING'
        """
    )
    suspend fun resetStaleProcessing(now: Long): Int

    @Query(
        """
        UPDATE screenshots
        SET status = 'PROCESSING', processing_error = NULL, updated_at = :now
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun markProcessing(id: Long, now: Long): Int

    @Query(
        """
        UPDATE screenshots
        SET status = 'FAILED', processing_error = :error, updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: Long, error: String, now: Long)

    /** Puts one screenshot back in the queue for a manual retry (§30). */
    @Query(
        """
        UPDATE screenshots
        SET status = 'PENDING', processing_error = NULL, updated_at = :now
        WHERE id = :id AND status <> 'PROCESSING'
        """
    )
    suspend fun requeue(id: Long, now: Long): Int

    // ------------------------------------------------------------ discovery

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(screenshots: List<ScreenshotEntity>): List<Long>

    @Query(
        """
        UPDATE screenshots
        SET relative_path = :relativePath,
            filename = :filename,
            date_added = :dateAdded,
            date_modified = :dateModified,
            file_size = :fileSize,
            width = :width,
            height = :height,
            mime_type = :mimeType,
            status = 'PENDING',
            processing_error = NULL,
            updated_at = :now
        WHERE media_store_id = :mediaStoreId
          AND (date_modified <> :dateModified
               OR file_size <> :fileSize
               OR status NOT IN ('COMPLETED'))
        """
    )
    suspend fun refreshChanged(
        mediaStoreId: Long,
        filename: String,
        relativePath: String?,
        dateAdded: Long,
        dateModified: Long,
        fileSize: Long,
        width: Int,
        height: Int,
        mimeType: String?,
        now: Long,
    ): Int

    @Query("DELETE FROM screenshots WHERE media_store_id IN (:mediaStoreIds)")
    suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>)

    @Query("DELETE FROM screenshots WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ------------------------------------------------------------- pipeline

    @Query(
        """
        SELECT * FROM screenshots
        WHERE content_hash = :contentHash AND status = 'COMPLETED' AND id <> :excludeId
        ORDER BY date_added ASC LIMIT 1
        """
    )
    suspend fun findCompletedByHash(contentHash: String, excludeId: Long): ScreenshotEntity?

    @Query(
        """
        UPDATE screenshots
        SET ocr_text = :ocrText,
            content_hash = :contentHash,
            duplicate_of_id = :duplicateOfId,
            status = 'COMPLETED',
            processing_error = NULL,
            updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun markCompleted(
        id: Long,
        ocrText: String,
        contentHash: String,
        duplicateOfId: Long?,
        now: Long,
    )

    @Query("DELETE FROM ocr_blocks WHERE screenshot_id = :screenshotId")
    suspend fun deleteOcrBlocks(screenshotId: Long)

    @Query("DELETE FROM extracted_urls WHERE screenshot_id = :screenshotId")
    suspend fun deleteUrls(screenshotId: Long)

    @Query("DELETE FROM extracted_dates WHERE screenshot_id = :screenshotId")
    suspend fun deleteDates(screenshotId: Long)

    @Query("DELETE FROM extracted_phones WHERE screenshot_id = :screenshotId")
    suspend fun deletePhones(screenshotId: Long)

    @Query("DELETE FROM extracted_prices WHERE screenshot_id = :screenshotId")
    suspend fun deletePrices(screenshotId: Long)

    @Query("DELETE FROM extracted_otps WHERE screenshot_id = :screenshotId")
    suspend fun deleteOtps(screenshotId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOcrBlocks(blocks: List<OcrBlockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUrls(urls: List<ExtractedUrlEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDates(dates: List<ExtractedDateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhones(phones: List<ExtractedPhoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrices(prices: List<ExtractedPriceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOtps(otps: List<ExtractedOtpEntity>)

    // -------------------------------------------------------------- browse

    /**
     * Keyset pagination for the screenshot browser. Keyset (not OFFSET) keeps
     * page N as cheap as page 1 at 50k+ rows (§31).
     */
    @Query(
        """
        SELECT * FROM screenshots s
        WHERE (s.date_added < :cursorDateAdded
               OR (s.date_added = :cursorDateAdded AND s.id < :cursorId))
          AND s.status <> 'FAILED'
        ORDER BY s.date_added DESC, s.id DESC
        LIMIT :limit
        """
    )
    fun observePage(cursorDateAdded: Long, cursorId: Long, limit: Int): Flow<List<ScreenshotEntity>>

    @Query(
        """
        SELECT * FROM screenshots s
        WHERE (s.date_added < :cursorDateAdded
               OR (s.date_added = :cursorDateAdded AND s.id < :cursorId))
          AND s.status <> 'FAILED'
        ORDER BY s.date_added DESC, s.id DESC
        LIMIT :limit
        """
    )
    suspend fun getPage(cursorDateAdded: Long, cursorId: Long, limit: Int): List<ScreenshotEntity>

    // -------------------------------------------------------------- search

    /**
     * Full-text search over OCR text and filename (§25).
     *
     * Ranking (§28) is deterministic and index-friendly: an exact filename
     * match outranks a filename prefix, which outranks any other hit; ties are
     * broken by recency. Avoids `LIKE '%query%'` scans.
     */
    @Query(
        """
        SELECT s.*,
            CASE
                WHEN LOWER(s.filename) = :lowerQuery THEN 3
                WHEN LOWER(s.filename) LIKE :prefixQuery ESCAPE '\' THEN 2
                WHEN LOWER(s.filename) LIKE :containsQuery ESCAPE '\' THEN 1
                ELSE 0
            END AS score
        FROM screenshots s
        JOIN screenshots_fts ON screenshots_fts.rowid = s.id
        WHERE screenshots_fts MATCH :ftsQuery
          AND (
              :filterType = 'ALL' OR
              (:filterType = 'URLS' AND EXISTS (SELECT 1 FROM extracted_urls x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'PRICES' AND EXISTS (SELECT 1 FROM extracted_prices x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'DATES' AND EXISTS (SELECT 1 FROM extracted_dates x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'PHONES' AND EXISTS (SELECT 1 FROM extracted_phones x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'OTPS' AND EXISTS (SELECT 1 FROM extracted_otps x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'DUPLICATES' AND s.duplicate_of_id IS NOT NULL)
          )
        ORDER BY score DESC, s.date_added DESC, s.id DESC
        LIMIT :limit
        """
    )
    fun search(
        ftsQuery: String,
        lowerQuery: String,
        prefixQuery: String,
        containsQuery: String,
        filterType: String,
        limit: Int,
    ): Flow<List<ScreenshotSearchRow>>

    /** Browse with a filter but no text query. */
    @Query(
        """
        SELECT * FROM screenshots s
        WHERE s.status <> 'FAILED'
          AND (
              :filterType = 'ALL' OR
              (:filterType = 'URLS' AND EXISTS (SELECT 1 FROM extracted_urls x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'PRICES' AND EXISTS (SELECT 1 FROM extracted_prices x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'DATES' AND EXISTS (SELECT 1 FROM extracted_dates x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'PHONES' AND EXISTS (SELECT 1 FROM extracted_phones x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'OTPS' AND EXISTS (SELECT 1 FROM extracted_otps x WHERE x.screenshot_id = s.id)) OR
              (:filterType = 'DUPLICATES' AND s.duplicate_of_id IS NOT NULL)
          )
        ORDER BY s.date_added DESC, s.id DESC
        LIMIT :limit
        """
    )
    fun observeFiltered(filterType: String, limit: Int): Flow<List<ScreenshotEntity>>

    // -------------------------------------------------------------- detail

    @Query("SELECT * FROM extracted_urls WHERE screenshot_id = :id ORDER BY id")
    fun observeUrls(id: Long): Flow<List<ExtractedUrlEntity>>

    @Query("SELECT * FROM extracted_dates WHERE screenshot_id = :id ORDER BY epoch_day")
    fun observeDates(id: Long): Flow<List<ExtractedDateEntity>>

    @Query("SELECT * FROM extracted_phones WHERE screenshot_id = :id ORDER BY id")
    fun observePhones(id: Long): Flow<List<ExtractedPhoneEntity>>

    @Query("SELECT * FROM extracted_prices WHERE screenshot_id = :id ORDER BY amount DESC")
    fun observePrices(id: Long): Flow<List<ExtractedPriceEntity>>

    @Query("SELECT * FROM extracted_otps WHERE screenshot_id = :id ORDER BY id")
    fun observeOtps(id: Long): Flow<List<ExtractedOtpEntity>>

    // ----------------------------------------------------------- maintenance

    @Query("DELETE FROM screenshots")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM screenshots WHERE status = 'COMPLETED'")
    fun observeIndexedBytes(): Flow<Long>

    @Transaction
    suspend fun clearAll() {
        // Child tables cascade, so a single delete is sufficient.
        deleteAll()
    }
}
