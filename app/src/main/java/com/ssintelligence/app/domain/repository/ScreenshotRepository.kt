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

    /** One-shot recent read, for suggestion generation and assistant context. */
    suspend fun getRecent(limit: Int): List<Screenshot>

    /** One-shot batch read by id, for multi-turn evidence reuse. */
    suspend fun getByIds(ids: List<Long>): List<Screenshot>
    fun observeScreenshot(id: Long): Flow<Screenshot?>
    fun observeDetail(id: Long): Flow<ScreenshotDetail?>
    fun observeDuplicateGroups(limit: Int): Flow<List<DuplicateGroup>>

    suspend fun getById(id: Long): Screenshot?
    suspend fun getByMediaStoreId(mediaStoreId: Long): Screenshot?

    /** One-shot detail read for use cases that cannot collect a Flow. */
    suspend fun getDetail(id: Long): ScreenshotDetail?

    /** Smart collections assembled from categories, hosts and phrases (§24 Phase 3). */
    suspend fun smartGroups(): List<com.ssintelligence.app.semantic.SmartGroup>

    // ------------------------------------------------------- Phase 4 visual

    /** Visual row for one screenshot, or null when never analyzed. */
    suspend fun visualFor(id: Long): VisualInfo?

    /** Visual rows for a candidate set, keyed by screenshot id. */
    suspend fun visualsFor(ids: List<Long>): Map<Long, VisualInfo>

    /**
     * Visually similar screenshots by perceptual-hash Hamming distance (§6).
     * Excludes [id] itself. Pure pixels — OCR need not match at all.
     */
    suspend fun visuallySimilar(id: Long, limit: Int = 8): List<VisualSimilar>

    /** Near-duplicate groups (Hamming ≤ threshold), newest first (§29). */
    suspend fun nearDuplicateGroups(limit: Int = 60): List<NearDuplicateGroup>

    // -------------------------------------------------------- Phase 4 graph

    /** The entity page for Explore (§17). */
    suspend fun entityPage(entityId: Long): com.ssintelligence.app.graph.EntityPage?

    suspend fun resolveEntity(
        type: com.ssintelligence.app.graph.GraphEntityType,
        normalizedName: String,
    ): com.ssintelligence.app.graph.GraphEntity?

    suspend fun screenshotIdsForEntity(entityId: Long, limit: Int = 200): List<Long>

    suspend fun topEntities(
        types: List<com.ssintelligence.app.graph.GraphEntityType>,
        minCount: Int = 2,
        limit: Int = 60,
    ): List<com.ssintelligence.app.graph.TopEntity>

    /** Entity display names filed on screenshots, for overlap scoring. */
    suspend fun entityLabelsFor(ids: List<Long>): Map<Long, Set<String>>

    suspend fun clearGraph()

    // --------------------------------------------------- Phase 4 collections

    suspend fun createCollection(name: String): Long
    suspend fun collections(): List<CollectionInfo>
    suspend fun deleteCollection(id: Long)
    suspend fun addToCollection(collectionId: Long, screenshotId: Long)
    suspend fun removeFromCollection(collectionId: Long, screenshotId: Long)
    suspend fun collectionMembers(collectionId: Long): List<Screenshot>
    suspend fun collectionsForScreenshot(screenshotId: Long): List<Long>

    // ------------------------------------------------------ Phase 4 organize

    /** Day-grouped recent screenshots with their top categories (§22). */
    suspend fun timeline(limit: Int = 120): List<TimelineDay>

    /** Screenshots sharing a booking/order identifier close in time (§23). */
    suspend fun eventGroups(): List<EventGroup>

    /** Same-day sequences with heavy OCR overlap (§24). */
    suspend fun sequences(): List<SequenceGroup>

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

    /**
     * Measured storage breakdown (§63).
     *
     * Every number comes from the database itself (`dbstat` page accounting),
     * never from estimates — except models, which are built in and cost zero
     * bytes on purpose. If page accounting is unavailable, components report
     * zero and the UI says the breakdown is unavailable rather than guessing.
     */
    suspend fun storageBreakdown(): StorageBreakdown
}

/** Per-component storage, all measured, all bytes. */
data class StorageBreakdown(
    val databaseBytes: Long,
    val screenshotsBytes: Long,
    val ocrIndexBytes: Long,
    val textVectorBytes: Long,
    val imageVectorBytes: Long,
    val graphBytes: Long,
    val historyBytes: Long,
    /** False when page accounting failed and components are zero. */
    val measured: Boolean,
)
