package com.ssintelligence.app.data.repository

import androidx.room.withTransaction
import com.ssintelligence.app.data.database.ScreenshotCategoryEntity
import com.ssintelligence.app.data.database.ScreenshotDao
import com.ssintelligence.app.data.database.ScreenshotEmbeddingEntity
import com.ssintelligence.app.data.database.SemanticDao
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.search.FtsQueryBuilder
import com.ssintelligence.app.semantic.CategoryAssignment
import com.ssintelligence.app.semantic.EmbeddingProvider
import com.ssintelligence.app.semantic.ExtractiveSummarizer
import com.ssintelligence.app.semantic.LocalSummarizer
import com.ssintelligence.app.semantic.RuleScreenshotClassifier
import com.ssintelligence.app.semantic.ScreenshotCategory
import com.ssintelligence.app.semantic.ScreenshotClassifier
import com.ssintelligence.app.semantic.ScreenshotDocument
import com.ssintelligence.app.semantic.SemanticMatch
import com.ssintelligence.app.semantic.SemanticModelInfo
import com.ssintelligence.app.semantic.SemanticRepository
import com.ssintelligence.app.semantic.TextEmbedding
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room-backed semantic index.
 *
 * Two properties keep this cheap:
 * - Vectors are compared only within a prefiltered candidate set. The
 *   prefilter is an FTS OR over the query's own terms plus its expansion
 *   terms, so the database narrows thousands of rows to hundreds before a
 *   single float is read (§10).
 * - A missing provider, a failed embed, or corrupt bytes degrade to "no
 *   semantic signal", never to an exception reaching the UI (§56).
 */
class SemanticRepositoryImpl(
    private val database: SsIntelligenceDatabase,
    private val dao: ScreenshotDao,
    private val semanticDao: SemanticDao,
    private val provider: EmbeddingProvider,
    private val classifier: ScreenshotClassifier = RuleScreenshotClassifier(),
    private val summarizer: LocalSummarizer = ExtractiveSummarizer(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SemanticRepository {

    override val isAvailable: Boolean get() = provider.isAvailable

    override val modelInfo: SemanticModelInfo = SemanticModelInfo(
        name = provider.model,
        version = provider.version,
        dimension = provider.dimension,
        sizeDescription = "built-in, no download needed",
    )

    override suspend fun indexScreenshot(document: ScreenshotDocument) {
        if (!isAvailable || document.ocrText.isBlank()) return
        val embedding = runCatching { provider.embed(document.embeddableText()) }.getOrNull()
            ?: return
        val categories = runCatching { classifier.classify(document) }.getOrDefault(emptyList())
        database.withTransaction {
            semanticDao.upsertEmbedding(
                ScreenshotEmbeddingEntity(
                    screenshotId = document.screenshotId,
                    vector = embedding.toBytes(),
                    model = embedding.model,
                    version = embedding.version,
                    dimension = embedding.dimension,
                    createdAt = clock(),
                ),
            )
            semanticDao.deleteAutoCategories(document.screenshotId)
            if (categories.isNotEmpty()) {
                semanticDao.insertCategories(
                    categories.map {
                        ScreenshotCategoryEntity(
                            screenshotId = document.screenshotId,
                            category = it.category.name,
                            confidence = it.confidence,
                            source = SOURCE_AUTO,
                            classifierVersion = it.classifierVersion,
                        )
                    },
                )
            }
        }
    }

    override suspend fun removeScreenshot(screenshotId: Long) {
        database.withTransaction {
            semanticDao.deleteEmbedding(screenshotId)
            semanticDao.deleteAutoCategories(screenshotId)
        }
    }

    override suspend fun embeddingFor(screenshotId: Long): TextEmbedding? {
        val row = semanticDao.embeddingFor(screenshotId) ?: return null
        if (row.model != provider.model || row.version != provider.version) return null
        return row.toEmbedding()
    }

    override suspend fun categoriesFor(screenshotId: Long): List<CategoryAssignment> =
        semanticDao.effectiveCategoriesFor(screenshotId).mapNotNull { row ->
            val category = runCatching { ScreenshotCategory.valueOf(row.category) }.getOrNull()
                ?: return@mapNotNull null
            CategoryAssignment(category, row.confidence, row.classifierVersion.ifBlank { "user" })
        }

    override suspend fun setUserCategory(screenshotId: Long, category: ScreenshotCategory) {
        database.withTransaction {
            semanticDao.deleteUserOverride(screenshotId)
            semanticDao.insertCategories(
                listOf(
                    ScreenshotCategoryEntity(
                        screenshotId = screenshotId,
                        category = category.name,
                        confidence = 1.0,
                        source = SOURCE_USER,
                        classifierVersion = "",
                    ),
                ),
            )
        }
    }

    override suspend fun clearUserCategory(screenshotId: Long) {
        semanticDao.deleteUserOverride(screenshotId)
    }

    override suspend fun findSimilar(
        embedding: TextEmbedding,
        excludeId: Long,
        limit: Int,
    ): List<SemanticMatch> {
        if (!isAvailable) return emptyList()
        // Candidates: same-category members plus recent rows, bounded. Loading
        // every vector in the library on each "find similar" tap is what this
        // avoids; category membership is the cheap prefilter.
        val categoryIds = semanticDao.categoriesFor(excludeId)
            .mapNotNull { runCatching { it.category }.getOrNull() }
        val candidateIds = buildSet {
            for (category in categoryIds) {
                addAll(semanticDao.idsInCategory(category, PREFILTER_LIMIT))
            }
            remove(excludeId)
        }.take(PREFILTER_LIMIT)
        if (candidateIds.isEmpty()) return emptyList()
        return rankBySimilarity(embedding, candidateIds, limit)
    }

    override suspend fun findSimilarTo(
        document: ScreenshotDocument,
        limit: Int,
    ): List<SemanticMatch> {
        if (!isAvailable) return emptyList()
        val stored = embeddingFor(document.screenshotId)
        val embedding = stored
            ?: runCatching { provider.embed(document.embeddableText()) }.getOrNull()
            ?: return emptyList()
        return findSimilar(embedding, document.screenshotId, limit)
    }

    override suspend fun searchSimilar(
        queryText: String,
        expansionTerms: List<String>,
        limit: Int,
    ): List<SemanticMatch> {
        if (!isAvailable || queryText.isBlank()) return emptyList()
        val queryEmbedding = runCatching { provider.embed(queryText) }.getOrNull()
            ?: return emptyList()
        // Prefilter through FTS: the query's own words plus the expansion
        // terms, OR-ed for recall. The vector step then re-ranks this bounded
        // set by meaning rather than by keyword overlap.
        val terms = (queryText.split(Regex("\\s+")) + expansionTerms)
            .map { it.lowercase().trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')') }
            .filter { it.length >= 2 }
            .distinct()
            .take(PREFILTER_TERMS)
        val match = FtsQueryBuilder.buildFromTerms(
            terms,
            FtsQueryBuilder.Conjunction.OR,
        ) ?: return emptyList()
        val ids = dao.searchIdsByText(match, PREFILTER_LIMIT)
        if (ids.isEmpty()) return emptyList()
        return rankBySimilarity(queryEmbedding, ids, limit)
    }

    private suspend fun rankBySimilarity(
        query: TextEmbedding,
        ids: List<Long>,
        limit: Int,
    ): List<SemanticMatch> {
        val rows = semanticDao.embeddingsFor(ids)
        val scored = mutableListOf<SemanticMatch>()
        for (row in rows) {
            if (row.model != query.model || row.version != query.version) continue
            if (row.dimension != query.dimension) continue
            val embedding = runCatching { row.toEmbedding() }.getOrNull() ?: continue
            val similarity = query.similarityTo(embedding)
            if (similarity >= MIN_SIMILARITY) {
                scored += SemanticMatch(row.screenshotId, similarity)
            }
        }
        return scored.sortedByDescending { it.similarity }.take(limit)
    }

    override suspend fun staleIds(limit: Int): List<Long> =
        semanticDao.staleEmbeddingIds(provider.model, provider.version, limit)

    override suspend fun embeddedCount(): Int = semanticDao.embeddingCount()

    override suspend fun clearSemanticIndex() {
        database.withTransaction {
            // Embeddings and automatic categories go; user corrections stay,
            // because the user explicitly made them (§52).
            semanticDao.deleteAllEmbeddings()
            semanticDao.deleteAllAutoCategories()
        }
    }

    override suspend fun summarize(
        document: ScreenshotDocument,
        phrases: List<String>,
    ): String = summarizer.summarize(document, phrases)

    private fun ScreenshotEmbeddingEntity.toEmbedding(): TextEmbedding {
        val buffer = ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN)
        val count = vector.size / 4
        val values = FloatArray(count) { buffer.float }
        return TextEmbedding(values, model, version)
    }

    private fun TextEmbedding.toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        return buffer.array()
    }

    private companion object {
        const val SOURCE_AUTO = "auto"
        const val SOURCE_USER = "user"

        /** Prefilter ceiling: vectors are read only for this many rows. */
        const val PREFILTER_LIMIT = 300
        const val PREFILTER_TERMS = 15

        /**
         * Cosine floor. Below this, "similar" is noise: nearly every pair of
         * screenshots shares some trigram features, so an unfiltered list
         * would claim everything is related to everything.
         */
        const val MIN_SIMILARITY = 0.25
    }
}
