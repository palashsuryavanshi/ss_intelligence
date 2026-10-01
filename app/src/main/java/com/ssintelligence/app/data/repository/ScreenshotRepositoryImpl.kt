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
import com.ssintelligence.app.data.database.ScreenshotVisualEntity
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
import com.ssintelligence.app.domain.repository.CollectionInfo
import com.ssintelligence.app.domain.repository.EventGroup
import com.ssintelligence.app.domain.repository.NearDuplicateGroup
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.repository.SequenceGroup
import com.ssintelligence.app.domain.repository.StorageBreakdown
import com.ssintelligence.app.domain.repository.TimelineDay
import com.ssintelligence.app.domain.repository.VisualInfo
import com.ssintelligence.app.domain.repository.VisualSimilar
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
     * Semantic and graph indexes, set after construction to break the circular
     * dependency: both need the database, and this repository calls into them
     * on save. Null means the corresponding indexing is disabled.
     */
    var semanticRepository: SemanticRepository? = null,
    var graphRepository: com.ssintelligence.app.graph.GraphRepository? = null,
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

    /**
     * Writes the visual row and files the screenshot's graph footprint.
     *
     * Categories come from the semantic pass that just ran, so classification
     * happens once; phrases come from the OCR text's own top terms, so the
     * graph's products are the screenshot's words, not guesses.
     */
    private suspend fun persistVisuals(result: ProcessingResult, now: Long) {
        val visualDao = database.visualDao()
        val canonical = result.duplicateOfId?.let { visualDao.visualFor(it) }
        val visual = result.visual
        if (canonical != null) {
            visualDao.upsertVisual(canonical.copy(id = 0L, screenshotId = result.screenshotId))
        } else if (visual != null) {
            visualDao.upsertVisual(
                ScreenshotVisualEntity(
                    screenshotId = result.screenshotId,
                    dhash = visual.imageHash,
                    colors = visual.colors.joinToString(","),
                    brightness = visual.brightness,
                    isDark = visual.isDark,
                    textCoverage = visual.textCoverage,
                    shotType = visual.type.name,
                    layout = visual.layout.name,
                    modelVersion = visual.modelVersion,
                    createdAt = now,
                ),
            )
        }
    }

    private suspend fun fileGraph(result: ProcessingResult) {
        val graph = graphRepository ?: return
        val semantic = semanticRepository ?: return
        val categories = semantic.categoriesFor(result.screenshotId)
            .filter { it.category != com.ssintelligence.app.semantic.ScreenshotCategory.OTHER }
            .map { it.category.label }
        graph.fileScreenshot(
            result.screenshotId,
            result.toDocument(),
            topWords(result.ocrText),
            categories,
        )
    }

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
        // Visuals and graph filing follow the same rule: derived data that must
        // never endanger the core index (§47). A duplicate copies the
        // canonical row's visuals — byte-identical images share pixels — while
        // its graph edges file under its own id so entity pages count it.
        runCatching {
            persistVisuals(result, now)
            fileGraph(result)
        }.onFailure {
            AppLog.w(TAG, "Visual/graph indexing failed screenshotId=${result.screenshotId}")
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

    override suspend fun storageBreakdown(): StorageBreakdown {
        val pages = runCatching { readDbstat() }.getOrNull() ?: return StorageBreakdown(
            databaseBytes = databaseSizeBytes(),
            screenshotsBytes = 0,
            ocrIndexBytes = 0,
            textVectorBytes = 0,
            imageVectorBytes = 0,
            graphBytes = 0,
            historyBytes = 0,
            measured = false,
        )
        fun group(vararg names: String): Long =
            pages.filterKeys { name -> names.any { name == it || name.startsWith(it) } }
                .values.sum()
        return StorageBreakdown(
            databaseBytes = databaseSizeBytes(),
            screenshotsBytes = group(
                "screenshots", "ocr_blocks", "extracted_urls", "extracted_dates",
                "extracted_phones", "extracted_prices", "extracted_otps",
            ),
            // FTS4 shadow tables: _content, _data, _idx, _docsize, _config.
            ocrIndexBytes = group("screenshots_fts"),
            textVectorBytes = group("screenshot_embeddings"),
            imageVectorBytes = group("screenshot_visuals"),
            graphBytes = group(
                "graph_entities", "graph_relations", "screenshot_categories",
                "collections", "collection_members",
            ),
            historyBytes = group("search_history"),
            measured = true,
        )
    }

    /**
     * Page accounting per table via `dbstat`.
     *
     * `dbstat` reports *object* names, not always table names: an FTS4 table
     * owns five shadow tables (`screenshots_fts_data` and friends), and a
     * `UNIQUE` constraint owns a `sqlite_autoindex_<table>_<n>` entry. Entries
     * are attributed to the table that owns them so the buckets add up to the
     * total rather than silently dropping pages.
     *
     * When `dbstat` is unavailable the caller falls back to file size only and
     * reports the breakdown as unmeasured, rather than guessing.
     */
    private fun readDbstat(): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        database.openHelper.writableDatabase.query("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    out[owningTable(name)] = (out[owningTable(name)] ?: 0L) + cursor.getLong(1)
                }
            }
        return out
    }

    /**
     * Maps a `dbstat` object name to the table that owns it.
     *
     * `sqlite_autoindex_screenshot_visuals_1` belongs to `screenshot_visuals`:
     * strip the prefix, then drop the trailing `_<n>` constraint number. FTS
     * shadow tables keep their name — the bucket matching already treats
     * `screenshots_fts_data` as part of `screenshots_fts`.
     */
    private fun owningTable(objectName: String): String {
        if (!objectName.startsWith(AUTOINDEX_PREFIX)) return objectName
        val rest = objectName.removePrefix(AUTOINDEX_PREFIX)
        val lastUnderscore = rest.lastIndexOf('_')
        return if (lastUnderscore > 0) rest.substring(0, lastUnderscore) else rest
    }

    // ------------------------------------------------------- Phase 4 visual

    override suspend fun visualFor(id: Long): VisualInfo? {
        val row = database.visualDao().visualFor(id) ?: return null
        return VisualInfo(
            screenshotId = row.screenshotId,
            dhash = row.dhash,
            colors = row.colors.split(',').filter { it.isNotBlank() },
            isDark = row.isDark,
            type = row.shotType,
            layout = row.layout,
        )
    }

    override suspend fun visualsFor(ids: List<Long>): Map<Long, VisualInfo> {
        if (ids.isEmpty()) return emptyMap()
        // One full-hash scan is 8 bytes per row — trivial even at 50k rows —
        // filtered in Kotlin to exactly the candidate set.
        val wanted = ids.toSet()
        return database.visualDao().allHashes()
            .filter { it.screenshotId in wanted }
            .associate {
                it.screenshotId to VisualInfo(
                    screenshotId = it.screenshotId,
                    dhash = it.dhash,
                    colors = emptyList(),
                    isDark = false,
                    type = "",
                    layout = "",
                )
            }
    }

    override suspend fun visuallySimilar(id: Long, limit: Int): List<VisualSimilar> {
        val self = database.visualDao().visualFor(id) ?: return emptyList()
        val ranked = database.visualDao().allHashes()
            .filter { it.screenshotId != id }
            .map { it.screenshotId to com.ssintelligence.app.vision.hammingDistance(self.dhash, it.dhash) }
            .filter { it.second <= VISUAL_SIMILAR_BITS }
            .sortedBy { it.second }
            .take(limit)
        if (ranked.isEmpty()) return emptyList()
        val rows = dao.getByIds(ranked.map { it.first }).associateBy { it.id }
        return ranked.mapNotNull { (memberId, distance) ->
            rows[memberId]?.let { VisualSimilar(it.toDomain(), distance) }
        }
    }

    override suspend fun nearDuplicateGroups(limit: Int): List<NearDuplicateGroup> {
        // Greedy clustering over group representatives: near-duplicate groups
        // are rare, so comparing each row against representatives (not every
        // row) stays cheap. Input is capped at recent rows — same documented
        // trade as the search candidate window.
        val hashes = database.visualDao().allHashes()
            .sortedByDescending { it.screenshotId }
            .take(NEAR_DUP_SCAN_CAP)
        val groups = mutableListOf<MutableList<com.ssintelligence.app.data.database.VisualHashRow>>()
        for (row in hashes) {
            val group = groups.firstOrNull { members ->
                members.any {
                    com.ssintelligence.app.vision.hammingDistance(it.dhash, row.dhash) <=
                        com.ssintelligence.app.vision.ImageEmbedding.NEAR_DUPLICATE_BITS
                }
            }
            if (group == null) {
                if (groups.size < limit) groups += mutableListOf(row)
            } else {
                group += row
            }
        }
        return groups
            .filter { it.size > 1 }
            .map { members ->
                val ids = members.map { it.screenshotId }
                var max = 0
                for (i in members.indices) for (j in i + 1 until members.size) {
                    max = maxOf(
                        max,
                        com.ssintelligence.app.vision.hammingDistance(
                            members[i].dhash,
                            members[j].dhash,
                        ),
                    )
                }
                NearDuplicateGroup(
                    coverId = ids.max(),
                    memberIds = ids.sortedDescending(),
                    maxDistanceBits = max,
                )
            }
            .sortedByDescending { it.size }
            .take(limit)
    }

    // -------------------------------------------------------- Phase 4 graph

    override suspend fun entityPage(entityId: Long) = graphRepository?.entityPage(entityId)

    override suspend fun resolveEntity(
        type: com.ssintelligence.app.graph.GraphEntityType,
        normalizedName: String,
    ) = graphRepository?.resolveEntity(type, normalizedName)

    override suspend fun screenshotIdsForEntity(entityId: Long, limit: Int): List<Long> =
        graphRepository?.screenshotIdsForEntity(entityId, limit).orEmpty()

    override suspend fun topEntities(
        types: List<com.ssintelligence.app.graph.GraphEntityType>,
        minCount: Int,
        limit: Int,
    ) = graphRepository?.topEntities(types, minCount, limit).orEmpty()

    override suspend fun entityLabelsFor(ids: List<Long>): Map<Long, Set<String>> {
        if (ids.isEmpty() || graphRepository == null) return emptyMap()
        return database.graphDao().relationsForScreenshots(ids)
            .groupBy({ it.screenshotId }, { it.displayName })
            .mapValues { it.value.toSet() }
    }

    override suspend fun clearGraph() {
        graphRepository?.clearGraph()
    }

    // --------------------------------------------------- Phase 4 collections

    override suspend fun createCollection(name: String): Long {
        val trimmed = name.trim().take(MAX_COLLECTION_NAME)
        require(trimmed.isNotEmpty()) { "Collection name must not be blank" }
        return database.collectionDao().insertCollection(
            com.ssintelligence.app.data.database.CollectionRow(
                name = trimmed,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun collections(): List<CollectionInfo> {
        val dao = database.collectionDao()
        return dao.allCollections().map { collection ->
            CollectionInfo(
                id = collection.id,
                name = collection.name,
                memberCount = dao.memberCount(collection.id),
                coverId = dao.memberIds(collection.id).firstOrNull(),
            )
        }
    }

    override suspend fun deleteCollection(id: Long) {
        database.collectionDao().deleteCollection(id)
    }

    override suspend fun addToCollection(collectionId: Long, screenshotId: Long) {
        database.collectionDao().addMember(
            com.ssintelligence.app.data.database.CollectionMemberRow(
                collectionId = collectionId,
                screenshotId = screenshotId,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeFromCollection(collectionId: Long, screenshotId: Long) {
        database.collectionDao().removeMember(collectionId, screenshotId)
    }

    override suspend fun collectionMembers(collectionId: Long): List<Screenshot> {
        val ids = database.collectionDao().memberIds(collectionId)
        if (ids.isEmpty()) return emptyList()
        val rows = dao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { rows[it]?.toDomain() }
    }

    override suspend fun collectionsForScreenshot(screenshotId: Long): List<Long> =
        database.collectionDao().collectionsForScreenshot(screenshotId)

    // ------------------------------------------------------ Phase 4 organize

    override suspend fun timeline(limit: Int): List<TimelineDay> {
        val rows = dao.recentRows(limit)
        if (rows.isEmpty()) return emptyList()
        val categories = database.semanticDao()
            .categoriesForIds(rows.map { it.id })
            .groupBy({ it.screenshotId }, { it.category })
        return rows.groupBy { it.dateAdded / 86_400L }
            .map { (day, members) ->
                val topCategories = members
                    .flatMap { categories[it.id].orEmpty() }
                    .filter { it != "OTHER" }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(2)
                    .map { it.key }
                TimelineDay(
                    epochDay = day,
                    screenshots = members.map { it.toDomain() },
                    categories = topCategories,
                )
            }
            .sortedByDescending { it.epochDay }
    }

    override suspend fun eventGroups(): List<EventGroup> {
        val graph = graphRepository ?: return emptyList()
        // Booking and order identifiers are the strongest event evidence: the
        // same PNR across screenshots taken days apart is one trip, not a
        // coincidence. Time proximity alone is never enough (§23).
        val codes = graph.topEntities(
            listOf(
                com.ssintelligence.app.graph.GraphEntityType.BOOKING,
                com.ssintelligence.app.graph.GraphEntityType.ORDER,
            ),
            minCount = 2,
            limit = 30,
        )
        val out = mutableListOf<EventGroup>()
        for (code in codes) {
            val ids = graph.screenshotIdsForEntity(code.entity.id, 50)
            if (ids.size < 2) continue
            val rows = dao.getByIds(ids)
            if (rows.size < 2) continue
            val span = rows.maxOf { it.dateAdded } - rows.minOf { it.dateAdded }
            if (span > EVENT_SPAN_SECONDS) continue
            out += EventGroup(
                label = code.entity.displayName,
                memberIds = rows.sortedByDescending { it.dateAdded }.map { it.id },
                startSeconds = rows.minOf { it.dateAdded },
                endSeconds = rows.maxOf { it.dateAdded },
            )
        }
        return out.sortedByDescending { it.endSeconds }
    }

    override suspend fun sequences(): List<SequenceGroup> {
        // Adjacent same-day screenshots whose OCR heavily overlaps: long chats,
        // tutorials, multi-page documents, shopping flows. Both conditions are
        // required — adjacency without overlap is just burst photography.
        val rows = dao.recentRows(SEQUENCE_SCAN).sortedBy { it.dateAdded }
        val groups = mutableListOf<SequenceGroup>()
        var current = mutableListOf<com.ssintelligence.app.data.database.ScreenshotEntity>()
        for (row in rows) {
            val last = current.lastOrNull()
            if (last != null && sameDay(last.dateAdded, row.dateAdded) &&
                row.dateAdded - last.dateAdded <= SEQUENCE_GAP_SECONDS &&
                termOverlap(last.ocrText, row.ocrText) >= SEQUENCE_OVERLAP
            ) {
                current += row
            } else {
                if (current.size >= 3) {
                    groups += SequenceGroup(
                        label = sequenceLabel(current),
                        memberIds = current.map { it.id },
                    )
                }
                current = mutableListOf(row)
            }
        }
        if (current.size >= 3) {
            groups += SequenceGroup(label = sequenceLabel(current), memberIds = current.map { it.id })
        }
        return groups
    }

    private fun sameDay(a: Long, b: Long): Boolean = a / 86_400L == b / 86_400L

    private fun termOverlap(a: String, b: String): Double {
        val setA = termSet(a)
        val setB = termSet(b)
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size.toDouble()
        return intersection / minOf(setA.size, setB.size)
    }

    private fun termSet(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun sequenceLabel(
        members: List<com.ssintelligence.app.data.database.ScreenshotEntity>,
    ): String {
        val words = members.flatMap { termSet(it.ocrText) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }
        return if (words.isEmpty()) {
            "${members.size} screenshots"
        } else {
            "${words.joinToString(" ")} · ${members.size} screenshots"
        }
    }

    private companion object {
        const val TAG = "ScreenshotRepository"
        const val MAX_ERROR_LENGTH = 500
        const val MAX_GROUP_CATEGORIES = 6
        const val MAX_GROUP_MEMBERS = 50

        /** Hamming ceiling for "visually similar" listings. */
        const val VISUAL_SIMILAR_BITS = 20

        /** Near-duplicate scan cap: same bounded-scan trade as search. */
        const val NEAR_DUP_SCAN_CAP = 5000

        /** Max collection name length: a title, not a paragraph. */
        const val MAX_COLLECTION_NAME = 60

        /** Event span: one trip's screenshots land within days, not months. */
        const val EVENT_SPAN_SECONDS = 14L * 86_400L

        /** Sequence scan window and link rules. */
        const val SEQUENCE_SCAN = 300
        const val SEQUENCE_GAP_SECONDS = 1800L
        const val SEQUENCE_OVERLAP = 0.5

        /** `dbstat` name prefix for entries owned by a UNIQUE/PRIMARY KEY. */
        const val AUTOINDEX_PREFIX = "sqlite_autoindex_"
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
