package com.ssintelligence.app.semantic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local semantic index (§9).
 *
 * Owns the whole derived-data lifecycle: embed on index, re-embed on OCR
 * change, classify, and answer "what is similar to this". The search engine
 * calls in; the indexing pipeline calls in; nobody touches the DAO directly.
 */
interface SemanticRepository {

    /** True when semantic features can run right now. */
    val isAvailable: Boolean

    /** Model identity for Settings display and for staleness checks. */
    val modelInfo: SemanticModelInfo

    /**
     * Derives and stores the embedding and automatic categories for one
     * screenshot. Called from the indexing pipeline's save path, so the
     * semantic index grows incrementally with the library (§40).
     *
     * Classification never touches `user` rows: corrections survive.
     */
    suspend fun indexScreenshot(document: ScreenshotDocument)

    /** Removes one screenshot's derived rows. Cascades handle deletion; this is for re-indexing. */
    suspend fun removeScreenshot(screenshotId: Long)

    suspend fun embeddingFor(screenshotId: Long): TextEmbedding?

    suspend fun categoriesFor(screenshotId: Long): List<CategoryAssignment>

    suspend fun setUserCategory(screenshotId: Long, category: ScreenshotCategory)

    suspend fun clearUserCategory(screenshotId: Long)

    /**
     * Most similar screenshots to [embedding], excluding [excludeId].
     * Only rows built by the current provider version are considered.
     */
    suspend fun findSimilar(
        embedding: TextEmbedding,
        excludeId: Long,
        limit: Int = DEFAULT_SIMILAR_LIMIT,
    ): List<SemanticMatch>

    /** Convenience overload: embeds [document] first, then searches. */
    suspend fun findSimilarTo(
        document: ScreenshotDocument,
        limit: Int = DEFAULT_SIMILAR_LIMIT,
    ): List<SemanticMatch>

    /**
     * Semantic candidates for a query: embeds the query text, prefilters by
     * the expanded terms through FTS, then ranks the bounded set by cosine.
     */
    suspend fun searchSimilar(
        queryText: String,
        expansionTerms: List<String>,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<SemanticMatch>

    /** Ids missing an embedding or built by an older provider version, bounded. */
    suspend fun staleIds(limit: Int): List<Long>

    suspend fun embeddedCount(): Int

    /** Deletes embeddings and automatic categories, keeping OCR and metadata (§52). */
    suspend fun clearSemanticIndex()

    suspend fun summarize(document: ScreenshotDocument, phrases: List<String> = emptyList()): String

    companion object {
        const val DEFAULT_SIMILAR_LIMIT = 8
        const val DEFAULT_SEARCH_LIMIT = 60
    }
}

data class SemanticModelInfo(
    val name: String,
    val version: String,
    val dimension: Int,
    /** Human size description, e.g. "built-in, no download". */
    val sizeDescription: String,
)
