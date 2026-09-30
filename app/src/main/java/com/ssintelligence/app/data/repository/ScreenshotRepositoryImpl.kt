package com.ssintelligence.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.ssintelligence.app.data.database.ExtractedDateEntity
import com.ssintelligence.app.data.database.ExtractedOtpEntity
import com.ssintelligence.app.data.database.ExtractedPhoneEntity
import com.ssintelligence.app.data.database.ExtractedPriceEntity
import com.ssintelligence.app.data.database.ExtractedUrlEntity
import com.ssintelligence.app.data.database.OcrBlockEntity
import com.ssintelligence.app.data.database.ScreenshotDao
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.data.database.toDomain
import com.ssintelligence.app.domain.model.DiscoverResult
import com.ssintelligence.app.domain.model.DuplicateGroup
import com.ssintelligence.app.domain.model.ExtractedDate
import com.ssintelligence.app.domain.model.ExtractedOtp
import com.ssintelligence.app.domain.model.ExtractedPhone
import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ExtractedUrl
import com.ssintelligence.app.domain.model.IndexingStats
import com.ssintelligence.app.domain.model.MediaImage
import com.ssintelligence.app.domain.model.ProcessingResult
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.ScreenshotDetail
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.semantic.ScreenshotDocument
import com.ssintelligence.app.semantic.SemanticRepository
import com.ssintelligence.app.semantic.SmartGroup
import com.ssintelligence.app.semantic.SmartGroupBuilder
import com.ssintelligence.app.semantic.ScreenshotCategory
import com.ssintelligence.app.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Room-backed implementation of the screenshot index. */
class ScreenshotRepositoryImpl(
    private val context: Context,
    private val database: SsIntelligenceDatabase,
    private val dao: ScreenshotDao,
    /**
     * Semantic index, set after construction to break the circular dependency:
     * the semantic repository needs the database, and this repository calls
     * into it on save. Null means semantic indexing is disabled.
     */
    var semanticRepository: SemanticRepository? = null,
) : ScreenshotRepository {

    // ------------------------------------------------------------- mapping

    private fun ProcessingResult.toDocument(): ScreenshotDocument = ScreenshotDocument(
        screenshotId = screenshotId,
        ocrText = ocrText,
        filename = "",
        hosts = urls.map { it.host },
        prices = prices.map {
            ExtractedPrice(0, screenshotId, it.rawText, it.currency, it.amount)
        },
        dateTexts = dates.map { it.rawText },
        phoneCount = phones.size,
        otpCount = otps.size,
    )

    // ------------------------------------------------------------ observing

    override fun observeStats(): Flow<IndexingStats> = combine(
        dao.observeStatusCounts(),
        dao.observeTotalCount(),
        dao.observeDuplicateItemCount(),
        dao.observeDuplicateGroupCount(),
    ) { counts, total, duplicateItems, duplicateGroups ->
        val byStatus = counts.associate { it.status to it.count }
        IndexingStats(
            total = total,
            completed = byStatus[ProcessingStatus.COMPLETED.name] ?: 0,
            pending = byStatus[ProcessingStatus.PENDING.name] ?: 0,
            processing = byStatus[ProcessingStatus.PROCESSING.name] ?: 0,
            failed = byStatus[ProcessingStatus.FAILED.name] ?: 0,
            duplicateItems = duplicateItems,
            duplicateGroups = duplicateGroups,
        )
    }

    override fun observeRecent(limit: Int): Flow<List<Screenshot>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeScreenshot(id: Long): Flow<Screenshot?> =
        dao.observeById(id).map { it?.toDomain() }

    override fun observeDetail(id: Long): Flow<ScreenshotDetail?> {
        // kotlinx.coroutines.flow.combine has typed overloads for up to five
        // flows, so the six projections are combined in two stages.
        val identityTables = combine(
            dao.observeUrls(id),
            dao.observeDates(id),
            dao.observePhones(id),
        ) { urls, dates, phones -> Triple(urls, dates, phones) }

        val valueTables = combine(
            dao.observePrices(id),
            dao.observeOtps(id),
        ) { prices, otps -> prices to otps }

        val tables = combine(identityTables, valueTables) { identity, values ->
            DetailTables(
                urls = identity.first,
                dates = identity.second,
                phones = identity.third,
                prices = values.first,
                otps = values.second,
            )
        }

        return combine(dao.observeById(id), tables) { entity, t ->
            entity?.let { row ->
                ScreenshotDetail(
                    screenshot = row.toDomain(),
                    urls = t.urls.map { ExtractedUrl(it.id, it.screenshotId, it.url, it.host) },
                    dates = t.dates.map {
                        ExtractedDate(it.id, it.screenshotId, it.rawText, it.epochDay, it.hasYear)
                    },
                    phones = t.phones.map {
                        ExtractedPhone(it.id, it.screenshotId, it.rawText, it.normalized, it.country)
                    },
                    prices = t.prices.map {
                        ExtractedPrice(it.id, it.screenshotId, it.rawText, it.currency, it.amount)
                    },
                    otps = t.otps.map { ExtractedOtp(it.id, it.screenshotId, it.code) },
                )
            }
        }
    }

    override fun observeDuplicateGroups(limit: Int): Flow<List<DuplicateGroup>> =
        dao.observeDuplicateMembers(limit).map { rows ->
            rows.groupBy { it.contentHash }
                .map { (hash, members) ->
                    DuplicateGroup(
                        contentHash = hash.orEmpty(),
                        screenshots = members.map { it.toDomain() },
                    )
                }
                .sortedByDescending { it.screenshots.size }
        }

    // -------------------------------------------------------------- queries

    override suspend fun getById(id: Long): Screenshot? = dao.getById(id)?.toDomain()

    override suspend fun getDetail(id: Long): ScreenshotDetail? {
        val row = dao.getById(id) ?: return null
        return ScreenshotDetail(
            screenshot = row.toDomain(),
            urls = dao.urlsForScreenshot(id).map { ExtractedUrl(it.id, it.screenshotId, it.url, it.host) },
            dates = dao.datesForScreenshot(id).map {
                ExtractedDate(it.id, it.screenshotId, it.rawText, it.epochDay, it.hasYear)
            },
            phones = dao.phonesForScreenshot(id).map {
                ExtractedPhone(it.id, it.screenshotId, it.rawText, it.normalized, it.country)
            },
            prices = dao.pricesForScreenshot(id).map {
                ExtractedPrice(it.id, it.screenshotId, it.rawText, it.currency, it.amount)
            },
            otps = dao.otpsForScreenshot(id).map { ExtractedOtp(it.id, it.screenshotId, it.code) },
        )
    }

    /**
     * Smart collections (§24).
     *
     * Takes the largest categories, loads each one's members with the hosts
     * and words the labeller needs, and lets [SmartGroupBuilder] decide what
     * is worth surfacing. Bounded throughout: a handful of categories, tens of
     * members each.
     */
    override suspend fun smartGroups(): List<SmartGroup> {
        val semantic = semanticRepository ?: return emptyList()
        if (!semantic.isAvailable) return emptyList()
        val semanticDao = database.semanticDao()
        val members = mutableListOf<SmartGroupBuilder.GroupMember>()
        for (count in semanticDao.categoryCounts().take(MAX_GROUP_CATEGORIES)) {
            val category = runCatching { ScreenshotCategory.valueOf(count.category) }.getOrNull()
                ?: continue
            if (category == ScreenshotCategory.OTHER) continue
            val ids = semanticDao.idsInCategory(count.category, MAX_GROUP_MEMBERS)
            if (ids.isEmpty()) continue
            val rows = dao.getByIds(ids).associateBy { it.id }
            val urls = dao.urlsFor(ids).groupBy { it.screenshotId }
            for (id in ids) {
                val row = rows[id] ?: continue
                members += SmartGroupBuilder.GroupMember(
                    id = id,
                    category = category,
                    hostRoots = urls[id].orEmpty().map { it.host.removePrefix("www.") }.distinct(),
                    words = topWords(row.ocrText),
                    dateAdded = row.dateAdded,
                )
            }
        }
        return SmartGroupBuilder.build(members)
    }

    /** Most frequent significant words: the group labeller's raw material. */
    private fun topWords(ocrText: String): List<String> {
        val stop = setOf(
            "the", "and", "for", "with", "from", "this", "that", "have", "your",
            "screenshot", "screenshots", "image", "photo", "https", "http", "www", "com",
        )
        return ocrText.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && it !in stop && !it.all { c -> c.isDigit() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key }
    }

    override suspend fun getByMediaStoreId(mediaStoreId: Long): Screenshot? =
        dao.getByMediaStoreId(mediaStoreId)?.toDomain()

    /**
     * Reconciles a MediaStore scan with the index (§8).
     *
     * - New media          → inserted as PENDING.
     * - Changed media      → metadata refreshed and re-queued as PENDING.
     * - Unchanged, done    → left untouched, so OCR never repeats.
     * - Deleted from device→ removed (cascades to extracted info).
     *
     * The whole reconciliation is a single transaction so a crash mid-scan
     * cannot leave the index half-updated.
     */
    override suspend fun applyDiscovery(images: List<MediaImage>): DiscoverResult =
        database.withTransaction {
            val now = System.currentTimeMillis()
            val discoveredIds = images.map { it.mediaStoreId }.toHashSet()

            val toInsert = images.map { image ->
                ScreenshotEntity(
                    mediaStoreId = image.mediaStoreId,
                    uri = image.uri,
                    filename = image.filename,
                    relativePath = image.relativePath,
                    dateAdded = image.dateAddedSec,
                    dateModified = image.dateModifiedSec,
                    fileSize = image.sizeBytes,
                    width = image.width,
                    height = image.height,
                    mimeType = image.mimeType,
                    status = ProcessingStatus.PENDING.name,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            val inserted = dao.insertIgnoring(toInsert).count { it != -1L }

            // Only consider rows that could plausibly be screenshots: rows the
            // user previously indexed stay in the index even if a later scan
            // under the current scope no longer matches, so switching between
            // "screenshots only" and "all images" never destroys data.
            val existing = dao.getByMediaStoreIds(discoveredIds.toList())
            var updated = 0
            for (row in existing) {
                val image = images.first { it.mediaStoreId == row.mediaStoreId }
                updated += dao.refreshChanged(
                    mediaStoreId = image.mediaStoreId,
                    filename = image.filename,
                    relativePath = image.relativePath,
                    dateAdded = image.dateAddedSec,
                    dateModified = image.dateModifiedSec,
                    fileSize = image.sizeBytes,
                    width = image.width,
                    height = image.height,
                    mimeType = image.mimeType,
                    now = now,
                )
            }

            val staleIds = existing.map { it.mediaStoreId } - discoveredIds
            val removed = if (staleIds.isEmpty()) 0 else {
                val toRemove = dao.getByMediaStoreIds(staleIds).map { it.id }
                // Delete by primary key: removing a MediaStore row also removes
                // its extracted information via ON DELETE CASCADE.
                toRemove.forEach { dao.deleteById(it) }
                toRemove.size
            }

            AppLog.i(TAG, "Discovery applied added=$inserted updated=$updated removed=$removed")
            DiscoverResult(added = inserted, updated = updated, removed = removed)
        }

    override suspend fun nextPending(limit: Int): List<Screenshot> =
        dao.nextPending(limit).map { it.toDomain() }

    override suspend fun markProcessing(id: Long) {
        dao.markProcessing(id, System.currentTimeMillis())
    }

    override suspend fun markFailed(id: Long, error: String) {
        dao.markFailed(id, error.take(MAX_ERROR_LENGTH), System.currentTimeMillis())
    }

    override suspend fun requeueStaleProcessing() {
        val requeued = dao.resetStaleProcessing(System.currentTimeMillis())
        if (requeued > 0) {
            AppLog.i(TAG, "Requeued $requeued interrupted screenshots")
        }
    }

    override suspend fun requeueScreenshot(id: Long) {
        if (dao.requeue(id, System.currentTimeMillis()) > 0) {
            AppLog.i(TAG, "Screenshot requeued for retry id=$id")
        }
    }

    override suspend fun requeueAllForReprocessing() {
        database.withTransaction {
            dao.requeueAllForReprocessing(System.currentTimeMillis())
        }
        AppLog.i(TAG, "Index rebuild requested; all screenshots requeued")
    }

    /**
     * Persists one processed screenshot atomically (§20): the screenshot row,
     * its OCR geometry and every extracted-information table are written in a
     * single transaction, so a crash can never leave a screenshot claiming to
     * be COMPLETED with only some of its data present.
     */
    override suspend fun saveResult(result: ProcessingResult) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.markCompleted(
                id = result.screenshotId,
                ocrText = result.ocrText,
                contentHash = result.contentHash,
                duplicateOfId = result.duplicateOfId,
                now = now,
            )
            // Replace rather than merge: OCR re-runs must not accumulate stale rows.
            dao.deleteOcrBlocks(result.screenshotId)
            dao.deleteUrls(result.screenshotId)
            dao.deleteDates(result.screenshotId)
            dao.deletePhones(result.screenshotId)
            dao.deletePrices(result.screenshotId)
            dao.deleteOtps(result.screenshotId)

            if (result.blocks.isNotEmpty()) {
                dao.insertOcrBlocks(
                    result.blocks.map {
                        OcrBlockEntity(
                            screenshotId = result.screenshotId,
                            level = it.level.name,
                            text = it.text,
                            left = it.left,
                            top = it.top,
                            right = it.right,
                            bottom = it.bottom,
                            confidence = it.confidence,
                        )
                    }
                )
            }
            if (result.urls.isNotEmpty()) {
                dao.insertUrls(
                    result.urls.map {
                        ExtractedUrlEntity(
                            screenshotId = result.screenshotId,
                            url = it.url,
                            host = it.host,
                        )
                    }
                )
            }
            if (result.dates.isNotEmpty()) {
                dao.insertDates(
                    result.dates.map {
                        ExtractedDateEntity(
                            screenshotId = result.screenshotId,
                            rawText = it.rawText,
                            epochDay = it.epochDay,
                            hasYear = it.hasYear,
                        )
                    }
                )
            }
            if (result.phones.isNotEmpty()) {
                dao.insertPhones(
                    result.phones.map {
                        ExtractedPhoneEntity(
                            screenshotId = result.screenshotId,
                            rawText = it.rawText,
                            normalized = it.normalized,
                            country = it.country,
                        )
                    }
                )
            }
            if (result.prices.isNotEmpty()) {
                dao.insertPrices(
                    result.prices.map {
                        ExtractedPriceEntity(
                            screenshotId = result.screenshotId,
                            rawText = it.rawText,
                            currency = it.currency,
                            amount = it.amount,
                        )
                    }
                )
            }
            if (result.otps.isNotEmpty()) {
                dao.insertOtps(
                    result.otps.map {
                        ExtractedOtpEntity(
                            screenshotId = result.screenshotId,
                            code = it.code,
                        )
                    }
                )
            }
        }
        // Semantic indexing runs after the transaction commits: it only reads
        // what was just written, and a failure here must never roll back the
        // lexical index (§56). Duplicates reuse the canonical row's semantics
        // implicitly through shared content, so they are still indexed — their
        // OCR text is what matters, not their originality.
        runCatching {
            semanticRepository?.indexScreenshot(result.toDocument())
        }.onFailure {
            AppLog.w(TAG, "Semantic indexing failed screenshotId=${result.screenshotId}")
        }
        AppLog.d(
            TAG,
            "Saved result screenshotId=${result.screenshotId} " +
                "urls=${result.urls.size} prices=${result.prices.size} otps=${result.otps.size}",
        )
    }

    override suspend fun findCompletedByHash(contentHash: String, excludeId: Long): Screenshot? =
        dao.findCompletedByHash(contentHash, excludeId)?.toDomain()

    override suspend fun countByStatus(): Map<String, Int> =
        dao.statusCounts().associate { it.status to it.count }

    override suspend fun pendingCount(): Int = dao.pendingCount()

    override suspend fun clearIndex() {
        database.withTransaction { dao.clearAll() }
        AppLog.i(TAG, "Index cleared by user request")
    }

    override suspend fun databaseSizeBytes(): Long {
        val dbFile = context.getDatabasePath(SsIntelligenceDatabase.NAME)
        val walFile = dbFile.parentFile?.resolve("${dbFile.name}-wal")
        return (if (dbFile.exists()) dbFile.length() else 0L) +
            (walFile?.let { if (it.exists()) it.length() else 0L } ?: 0L)
    }

    private companion object {
        const val TAG = "ScreenshotRepository"
        const val MAX_ERROR_LENGTH = 500
        const val MAX_GROUP_CATEGORIES = 6
        const val MAX_GROUP_MEMBERS = 50
    }

    /** Holder for the five extracted-information projections. */
    private data class DetailTables(
        val urls: List<ExtractedUrlEntity>,
        val dates: List<ExtractedDateEntity>,
        val phones: List<ExtractedPhoneEntity>,
        val prices: List<ExtractedPriceEntity>,
        val otps: List<ExtractedOtpEntity>,
    )
}
