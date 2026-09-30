package com.ssintelligence.app.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Local text embedding for one screenshot (§8, §9).
 *
 * Stored as a normalized float32 blob, not as columns: 512 floats would be 512
 * columns, and no query ever filters *on* a component — vectors are loaded by
 * screenshot id and compared in Kotlin. The blob is 2 KB per row at the
 * default dimension.
 *
 * One row per screenshot, replaced wholesale on re-embed. [model] and
 * [version] identify the weights so a provider change can find and rebuild
 * stale rows instead of mixing incompatible vectors (§36).
 */
@Entity(
    tableName = "screenshot_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["screenshot_id"], unique = true)],
)
data class ScreenshotEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id")
    val screenshotId: Long,
    /** Little-endian float32 array, L2-normalized. */
    @ColumnInfo(name = "vector", typeAffinity = ColumnInfo.BLOB)
    val vector: ByteArray,
    @ColumnInfo(name = "model")
    val model: String,
    @ColumnInfo(name = "version")
    val version: String,
    @ColumnInfo(name = "dimension")
    val dimension: Int,
    /** Epoch millis. Compared against OCR write time for staleness checks. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotEmbeddingEntity) return false
        return id == other.id && screenshotId == other.screenshotId &&
            vector.contentEquals(other.vector) && model == other.model &&
            version == other.version && dimension == other.dimension &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + screenshotId.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + dimension
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * One category assignment for one screenshot (§19, §21).
 *
 * Automatic assignments and user overrides share the table, distinguished by
 * [source]. A re-run of the classifier replaces only `auto` rows; `user` rows
 * are never touched, so a correction survives re-indexing forever (§21).
 */
@Entity(
    tableName = "screenshot_categories",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["screenshot_id"]),
        Index(value = ["category"]),
    ],
)
data class ScreenshotCategoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id")
    val screenshotId: Long,
    /** [com.ssintelligence.app.semantic.ScreenshotCategory] name. */
    @ColumnInfo(name = "category")
    val category: String,
    /** Internal 0..1 confidence. Never displayed as a percentage. */
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    /** `auto` or `user`. */
    @ColumnInfo(name = "source")
    val source: String,
    /** Classifier build, e.g. `rules-v1`. Empty for user rows. */
    @ColumnInfo(name = "classifier_version")
    val classifierVersion: String,
)

@Dao
interface SemanticDao {

    // ------------------------------------------------------------ embeddings

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(embedding: ScreenshotEmbeddingEntity)

    @Query("SELECT * FROM screenshot_embeddings WHERE screenshot_id = :screenshotId")
    suspend fun embeddingFor(screenshotId: Long): ScreenshotEmbeddingEntity?

    /** Vectors for exactly the candidate set — never a table-wide read (§10). */
    @Query("SELECT * FROM screenshot_embeddings WHERE screenshot_id IN (:ids)")
    suspend fun embeddingsFor(ids: List<Long>): List<ScreenshotEmbeddingEntity>

    @Query("DELETE FROM screenshot_embeddings WHERE screenshot_id = :screenshotId")
    suspend fun deleteEmbedding(screenshotId: Long)

    @Query("DELETE FROM screenshot_embeddings")
    suspend fun deleteAllEmbeddings()

    @Query("SELECT COUNT(*) FROM screenshot_embeddings")
    suspend fun embeddingCount(): Int

    /**
     * Rows whose embedding is missing or was built by an older provider.
     * Bounded so a rebuild is resumable in chunks rather than all-or-nothing.
     *
     * Screenshots with no OCR text are excluded: an empty string embeds to the
     * zero vector, which matches nothing and teaches nothing. Without this,
     * a textless screenshot would stay "stale" forever and a rebuild worker
     * would loop on it indefinitely.
     */
    @Query(
        """
        SELECT s.id FROM screenshots s
        LEFT JOIN screenshot_embeddings e ON e.screenshot_id = s.id
        WHERE s.status = 'COMPLETED'
          AND TRIM(s.ocr_text) <> ''
          AND (e.screenshot_id IS NULL OR e.model <> :model OR e.version <> :version)
        ORDER BY s.id ASC
        LIMIT :limit
        """
    )
    suspend fun staleEmbeddingIds(model: String, version: String, limit: Int): List<Long>

    // ------------------------------------------------------------ categories

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<ScreenshotCategoryEntity>)

    @Query("SELECT * FROM screenshot_categories WHERE screenshot_id = :screenshotId ORDER BY confidence DESC")
    suspend fun categoriesFor(screenshotId: Long): List<ScreenshotCategoryEntity>

    /** User rows win: a single category list with overrides applied. */
    @Query(
        """
        SELECT * FROM screenshot_categories WHERE screenshot_id = :screenshotId
        ORDER BY CASE source WHEN 'user' THEN 0 ELSE 1 END, confidence DESC
        """
    )
    suspend fun effectiveCategoriesFor(screenshotId: Long): List<ScreenshotCategoryEntity>

    @Query("DELETE FROM screenshot_categories WHERE screenshot_id = :screenshotId AND source = 'auto'")
    suspend fun deleteAutoCategories(screenshotId: Long)

    @Query("DELETE FROM screenshot_categories WHERE screenshot_id = :screenshotId AND source = 'user'")
    suspend fun deleteUserOverride(screenshotId: Long)

    @Query("DELETE FROM screenshot_categories")
    suspend fun deleteAllCategories()

    /** Removes every automatic assignment while keeping user corrections (§52). */
    @Query("DELETE FROM screenshot_categories WHERE source = 'auto'")
    suspend fun deleteAllAutoCategories()

    /** Category members, newest first — the input to smart groups. */
    @Query(
        """
        SELECT screenshot_id FROM screenshot_categories
        WHERE category = :category
        GROUP BY screenshot_id
        ORDER BY MAX(id) DESC
        LIMIT :limit
        """
    )
    suspend fun idsInCategory(category: String, limit: Int): List<Long>

    @Query(
        """
        SELECT category, COUNT(DISTINCT screenshot_id) AS cnt FROM screenshot_categories
        GROUP BY category
        ORDER BY cnt DESC
        """
    )
    suspend fun categoryCounts(): List<CategoryCountRow>
}

/** A category and how many screenshots hold it. */
data class CategoryCountRow(
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "cnt") val count: Int,
)
