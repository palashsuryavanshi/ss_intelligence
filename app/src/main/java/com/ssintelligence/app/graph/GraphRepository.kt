package com.ssintelligence.app.graph

import com.ssintelligence.app.data.database.GraphDao
import com.ssintelligence.app.data.database.GraphEntityRow
import com.ssintelligence.app.data.database.ScreenshotDao
import com.ssintelligence.app.data.database.SemanticDao
import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.semantic.EntityExtractor
import com.ssintelligence.app.semantic.ScreenshotCategory
import com.ssintelligence.app.semantic.ScreenshotDocument

/**
 * Local knowledge graph (§12–§16).
 *
 * Files entities found in a screenshot's extracted data, links them with
 * relationships, and answers entity questions: "everything about Pixel 9a",
 * "prices seen", "more from Amazon". All joins, no graph database.
 *
 * Integrity rules, enforced here rather than hoped for:
 * - Filing is find-or-create on (type, normalized name): shared entities stay
 *   shared, variants that normalize differently stay separate.
 * - One screenshot's footprint is replaced atomically; re-indexing never
 *   duplicates edges.
 * - Deleting a screenshot cascades its edges; entities nobody references are
 *   swept, entities others still reference survive (§59).
 * - SIMILAR_TO and DUPLICATE_OF are derived on read from embeddings and
 *   hashes, never stored.
 */
interface GraphRepository {

    /** Files one screenshot's entities and relationships, replacing its old ones. */
    suspend fun fileScreenshot(
        screenshotId: Long,
        document: ScreenshotDocument,
        phrases: List<String>,
        categories: List<String>,
    )

    /**
     * Files one screenshot by reading its extraction tables directly.
     *
     * Used by the backfill worker for rows indexed before Phase 4: same
     * sources the live pipeline uses, same result.
     */
    suspend fun fileScreenshotFromTables(screenshotId: Long)

    suspend fun entityPage(entityId: Long, limit: Int = 200): EntityPage?

    suspend fun resolveEntity(type: GraphEntityType, normalizedName: String): GraphEntity?

    /** Screenshots referencing an entity — "more about Pixel 9a" (§33). */
    suspend fun screenshotIdsForEntity(entityId: Long, limit: Int = 200): List<Long>

    /** Entity display names per screenshot, for overlap scoring. One query. */
    suspend fun entityLabelsFor(ids: List<Long>): Map<Long, Set<String>>

    /** Top entities of given types for the Explore screen (§52). */
    suspend fun topEntities(
        types: List<GraphEntityType>,
        minCount: Int = 2,
        limit: Int = 60,
    ): List<TopEntity>

    /** Clears the whole graph; screenshots and extraction tables are untouched (§64). */
    suspend fun clearGraph()
}

data class TopEntity(
    val entity: GraphEntity,
    val screenshotCount: Int,
)

class GraphRepositoryImpl(
    private val graphDao: GraphDao,
    private val screenshotDao: ScreenshotDao,
    private val semanticDao: SemanticDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : GraphRepository {

    override suspend fun fileScreenshot(
        screenshotId: Long,
        document: ScreenshotDocument,
        phrases: List<String>,
        categories: List<String>,
    ) {
        val refs = EntityExtractor.extract(document, phrases)
        val input = entityRefsToGraphInput(refs, categories)
        val built = GraphBuilder.build(input)
        if (built.isEmpty()) {
            // Still clear stale edges: a re-index that finds nothing must not
            // leave the previous footprint behind.
            graphDao.deleteRelationsFor(screenshotId)
            graphDao.deleteOrphanEntities()
            return
        }
        val now = clock()
        graphDao.replaceScreenshotGraph(
            screenshotId = screenshotId,
            entities = built.map {
                GraphEntityRow(
                    type = it.entity.type.name,
                    displayName = it.entity.displayName,
                    normalizedName = it.entity.normalizedName,
                    createdAt = now,
                )
            },
            kinds = built.map { it.kind.name to it.confidence },
        )
    }

    override suspend fun entityPage(entityId: Long, limit: Int): EntityPage? {
        val row = graphDao.entityById(entityId) ?: return null
        val type = runCatching { GraphEntityType.valueOf(row.type) }.getOrNull() ?: return null
        val entity = GraphEntity(row.id, type, row.displayName, row.normalizedName)
        val memberIds = graphDao.screenshotIdsForEntity(entityId, limit)
        val prices = graphDao.pricesForEntityMembers(entityId).map { price ->
            PricePoint(price.label, price.seenAtSeconds)
        }
        return EntityPage(
            entity = entity,
            screenshotIds = memberIds,
            pricesSeen = prices,
            websites = graphDao.websitesForEntityMembers(entityId),
            categories = graphDao.categoriesForEntityMembers(entityId),
        )
    }

    override suspend fun resolveEntity(
        type: GraphEntityType,
        normalizedName: String,
    ): GraphEntity? {
        val row = graphDao.entityByKey(type.name, normalizedName) ?: return null
        return GraphEntity(row.id, type, row.displayName, row.normalizedName)
    }

    override suspend fun fileScreenshotFromTables(screenshotId: Long) {
        val row = screenshotDao.getById(screenshotId) ?: return
        val urls = screenshotDao.urlsForScreenshot(screenshotId)
        val prices = screenshotDao.pricesForScreenshot(screenshotId)
        val dates = screenshotDao.datesForScreenshot(screenshotId)
        val phones = screenshotDao.phonesForScreenshot(screenshotId)
        val otps = screenshotDao.otpsForScreenshot(screenshotId)
        val categories = semanticDao.effectiveCategoriesFor(screenshotId)
            .mapNotNull { runCatching { ScreenshotCategory.valueOf(it.category) }.getOrNull() }
            .filter { it != ScreenshotCategory.OTHER }
            .map { it.label }
        val document = ScreenshotDocument(
            screenshotId = screenshotId,
            ocrText = row.ocrText,
            filename = row.filename,
            hosts = urls.map { it.host },
            prices = prices.map {
                ExtractedPrice(it.id, it.screenshotId, it.rawText, it.currency, it.amount)
            },
            dateTexts = dates.map { it.rawText },
            phoneCount = phones.size,
            otpCount = otps.size,
            dateAdded = row.dateAdded,
        )
        fileScreenshot(screenshotId, document, topTerms(row.ocrText), categories)
    }

    /** Most frequent significant words: product candidates for the graph. */
    private fun topTerms(ocrText: String): List<String> {
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

    override suspend fun screenshotIdsForEntity(entityId: Long, limit: Int): List<Long> =
        graphDao.screenshotIdsForEntity(entityId, limit)

    override suspend fun entityLabelsFor(ids: List<Long>): Map<Long, Set<String>> {
        if (ids.isEmpty()) return emptyMap()
        return graphDao.relationsForScreenshots(ids)
            .groupBy({ it.screenshotId }, { it.displayName.lowercase() })
            .mapValues { it.value.toSet() }
    }

    override suspend fun topEntities(
        types: List<GraphEntityType>,
        minCount: Int,
        limit: Int,
    ): List<TopEntity> =
        graphDao.topEntities(types.map { it.name }, minCount, limit).mapNotNull { row ->
            val type = runCatching { GraphEntityType.valueOf(row.type) }.getOrNull()
                ?: return@mapNotNull null
            TopEntity(GraphEntity(row.id, type, row.displayName, ""), row.count)
        }

    override suspend fun clearGraph() {
        graphDao.deleteAllRelations()
        graphDao.deleteAllEntities()
    }
}
