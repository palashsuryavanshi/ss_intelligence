package com.ssintelligence.app.domain.repository

import com.ssintelligence.app.domain.model.DiscoverResult
import com.ssintelligence.app.domain.model.DuplicateGroup
import com.ssintelligence.app.domain.model.IndexingStats
import com.ssintelligence.app.domain.model.MediaImage
import com.ssintelligence.app.domain.model.ProcessingResult
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.ScreenshotDetail
import kotlinx.coroutines.flow.Flow

/** Local index of screenshots and their extracted information. */
interface ScreenshotRepository {
    fun observeStats(): Flow<IndexingStats>
    fun observeRecent(limit: Int): Flow<List<Screenshot>>
    fun observeScreenshot(id: Long): Flow<Screenshot?>
    fun observeDetail(id: Long): Flow<ScreenshotDetail?>
    fun observeDuplicateGroups(limit: Int): Flow<List<DuplicateGroup>>

    suspend fun getById(id: Long): Screenshot?
    suspend fun getByMediaStoreId(mediaStoreId: Long): Screenshot?

    /** One-shot detail read for use cases that cannot collect a Flow. */
    suspend fun getDetail(id: Long): ScreenshotDetail?

    /** Smart collections assembled from categories, hosts and phrases (§24 Phase 3). */
    suspend fun smartGroups(): List<com.ssintelligence.app.semantic.SmartGroup>

    /**
     * Reconciles [images] with the index: inserts new rows as PENDING,
     * refreshes metadata for changed rows (which resets them to PENDING),
     * and removes rows whose media no longer exists on device.
     */
    suspend fun applyDiscovery(images: List<MediaImage>): DiscoverResult

    /** Next batch of rows awaiting processing, oldest first. */
    suspend fun nextPending(limit: Int): List<Screenshot>

    suspend fun markProcessing(id: Long)
    suspend fun markFailed(id: Long, error: String)
    suspend fun saveResult(result: ProcessingResult)

    /**
     * Returns rows abandoned in PROCESSING by a killed process to PENDING so
     * the next worker run picks them up (§21, §30).
     */
    suspend fun requeueStaleProcessing()

    /** Re-queues a single screenshot for a user-triggered retry (§30). */
    suspend fun requeueScreenshot(id: Long)

    /**
     * Re-queues every indexed screenshot so OCR and extraction run again.
     * Used by "Rebuild index" in Settings (§34).
     */
    suspend fun requeueAllForReprocessing()

    /** A COMPLETED screenshot with identical content, if any. */
    suspend fun findCompletedByHash(contentHash: String, excludeId: Long): Screenshot?

    suspend fun countByStatus(): Map<String, Int>
    suspend fun pendingCount(): Int
    suspend fun clearIndex()
    suspend fun databaseSizeBytes(): Long
}
