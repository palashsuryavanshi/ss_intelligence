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
import androidx.room.Transaction

/**
 * Visual analysis for one screenshot (§9–§11, §25).
 *
 * One row per screenshot, replaced wholesale on re-analysis. The dHash fits in
 * a single INTEGER: Hamming scans over 8-byte ints need no index and no vector
 * extension — 50,000 rows scan in well under a millisecond of CPU (§10 of the
 * Phase 3 spec applies here too: measure before introducing machinery).
 */
@Entity(
    tableName = "screenshot_visuals",
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
data class ScreenshotVisualEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id")
    val screenshotId: Long,
    /** 64-bit dHash, stored signed. Hamming distance is computed in Kotlin. */
    @ColumnInfo(name = "dhash")
    val dhash: Long,
    /** Comma-joined color names, e.g. `blue,white,gray`. */
    @ColumnInfo(name = "colors")
    val colors: String,
    @ColumnInfo(name = "brightness")
    val brightness: Double,
    @ColumnInfo(name = "is_dark")
    val isDark: Boolean,
    @ColumnInfo(name = "text_coverage")
    val textCoverage: Double,
    @ColumnInfo(name = "shot_type")
    val shotType: String,
    @ColumnInfo(name = "layout")
    val layout: String,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * A knowledge-graph entity (§16).
 *
 * Shared across screenshots: ten screenshots showing ₹39,999 reference one
 * PRICE row. Unique per (type, normalized name), so filing is find-or-create
 * and merging is impossible by construction — two spellings that normalize
 * differently stay two entities until the normalization rules say otherwise.
 */
@Entity(
    tableName = "graph_entities",
    indices = [Index(value = ["type", "normalized_name"], unique = true)],
)
data class GraphEntityRow(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * One screenshot–entity link (§16).
 *
 * SIMILAR_TO and DUPLICATE_OF are not stored: similarity is recomputed from
 * embeddings and duplication from content hashes, both live. Storing them
 * would duplicate the source of truth and go stale.
 */
@Entity(
    tableName = "graph_relations",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GraphEntityRow::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["screenshot_id"]),
        Index(value = ["entity_id"]),
    ],
)
data class GraphRelationRow(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id")
    val screenshotId: Long,
    @ColumnInfo(name = "entity_id")
    val entityId: Long,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
)

/** A user-created collection (§19). Stores references, never images. */
@Entity(tableName = "collections")
data class CollectionRow(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "collection_members",
    foreignKeys = [
        ForeignKey(
            entity = CollectionRow::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["collection_id"]),
        Index(value = ["screenshot_id"]),
        // One membership per pair: adding twice is a no-op, so counts and
        // member lists can never contain the same screenshot twice.
        Index(value = ["collection_id", "screenshot_id"], unique = true),
    ],
)
data class CollectionMemberRow(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "collection_id")
    val collectionId: Long,
    @ColumnInfo(name = "screenshot_id")
    val screenshotId: Long,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)

/** An entity with how many screenshots reference it. */
data class EntityCountRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "cnt") val count: Int,
)

@Dao
interface VisualDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVisual(visual: ScreenshotVisualEntity)

    @Query("SELECT * FROM screenshot_visuals WHERE screenshot_id = :screenshotId")
    suspend fun visualFor(screenshotId: Long): ScreenshotVisualEntity?

    /** Full visual rows for a candidate set — one query, not hundreds. */
    @Query("SELECT * FROM screenshot_visuals WHERE screenshot_id IN (:ids)")
    suspend fun visualsForIds(ids: List<Long>): List<ScreenshotVisualEntity>

    /** All hashes: 8 bytes each, the whole library fits in memory trivially. */
    @Query("SELECT screenshot_id, dhash FROM screenshot_visuals")
    suspend fun allHashes(): List<VisualHashRow>

    /** Most-recent hashes, capped in SQL so large libraries never load fully. */
    @Query("SELECT screenshot_id, dhash FROM screenshot_visuals ORDER BY screenshot_id DESC LIMIT :limit")
    suspend fun recentHashes(limit: Int): List<VisualHashRow>

    @Query("DELETE FROM screenshot_visuals WHERE screenshot_id = :screenshotId")
    suspend fun deleteVisual(screenshotId: Long)

    @Query("DELETE FROM screenshot_visuals")
    suspend fun deleteAllVisuals()

    @Query("SELECT COUNT(*) FROM screenshot_visuals")
    suspend fun visualCount(): Int

    @Query(
        """
        SELECT s.id FROM screenshots s
        LEFT JOIN screenshot_visuals v ON v.screenshot_id = s.id
        WHERE s.status = 'COMPLETED'
          AND (v.screenshot_id IS NULL OR v.model_version <> :version)
        ORDER BY s.id ASC
        LIMIT :limit
        """
    )
    suspend fun staleVisualIds(version: String, limit: Int): List<Long>

    /** Screenshots whose palette contains a color (§9). */
    @Query(
        """
        SELECT s.* FROM screenshots s
        JOIN screenshot_visuals v ON v.screenshot_id = s.id
        WHERE s.status <> 'FAILED'
          AND (',' || v.colors || ',') LIKE '%,' || :color || ',%'
        ORDER BY s.date_added DESC, s.id DESC
        LIMIT :limit
        """
    )
    suspend fun withColor(color: String, limit: Int): List<ScreenshotEntity>
}

data class VisualHashRow(
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "dhash") val dhash: Long,
)

@Dao
interface GraphDao {

    // ------------------------------------------------------------- entities

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntitiesIgnore(entities: List<GraphEntityRow>): List<Long>

    @Query("SELECT * FROM graph_entities WHERE type = :type AND normalized_name = :normalized LIMIT 1")
    suspend fun entityByKey(type: String, normalized: String): GraphEntityRow?

    @Query("SELECT * FROM graph_entities WHERE id = :id")
    suspend fun entityById(id: Long): GraphEntityRow?

    @Query("SELECT COUNT(*) FROM graph_relations")
    suspend fun relationCount(): Int

    @Query("SELECT COUNT(*) FROM graph_entities")
    suspend fun entityCount(): Int

    @Query("DELETE FROM graph_relations WHERE screenshot_id = :screenshotId")
    suspend fun deleteRelationsFor(screenshotId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelations(relations: List<GraphRelationRow>)

    @Query("DELETE FROM graph_entities WHERE id NOT IN (SELECT DISTINCT entity_id FROM graph_relations)")
    suspend fun deleteOrphanEntities(): Int

    @Query("DELETE FROM graph_entities")
    suspend fun deleteAllEntities()

    @Query("DELETE FROM graph_relations")
    suspend fun deleteAllRelations()

    // -------------------------------------------------------------- reading

    /** Screenshots referencing an entity, newest first. */
    @Query(
        """
        SELECT r.screenshot_id FROM graph_relations r
        JOIN screenshots s ON s.id = r.screenshot_id
        WHERE r.entity_id = :entityId
        ORDER BY s.date_added DESC, s.id DESC
        LIMIT :limit
        """
    )
    suspend fun screenshotIdsForEntity(entityId: Long, limit: Int): List<Long>

    /** Relations of one screenshot with entity display names attached. */
    @Query(
        """
        SELECT r.screenshot_id AS screenshotId, r.kind AS kind, r.confidence AS confidence,
               e.id AS entityId, e.type AS entityType, e.display_name AS displayName
        FROM graph_relations r
        JOIN graph_entities e ON e.id = r.entity_id
        WHERE r.screenshot_id = :screenshotId
        """
    )
    suspend fun relationsForScreenshot(screenshotId: Long): List<RelationWithEntity>

    /** Batch relation read for candidate sets — one query, not hundreds. */
    @Query(
        """
        SELECT r.screenshot_id AS screenshotId, r.kind AS kind, r.confidence AS confidence,
               e.id AS entityId, e.type AS entityType, e.display_name AS displayName
        FROM graph_relations r
        JOIN graph_entities e ON e.id = r.entity_id
        WHERE r.screenshot_id IN (:ids)
        """
    )
    suspend fun relationsForScreenshots(ids: List<Long>): List<RelationWithEntity>

    /** Entities of given types, most-referenced first — the Explore screen. */
    @Query(
        """
        SELECT e.id AS id, e.type AS type, e.display_name AS display_name,
               COUNT(DISTINCT r.screenshot_id) AS cnt
        FROM graph_entities e
        JOIN graph_relations r ON r.entity_id = e.id
        WHERE e.type IN (:types)
        GROUP BY e.id
        HAVING cnt >= :minCount
        ORDER BY cnt DESC, e.display_name ASC
        LIMIT :limit
        """
    )
    suspend fun topEntities(types: List<String>, minCount: Int, limit: Int): List<EntityCountRow>

    /** Prices seen alongside an entity's screenshots, in screenshot-date order (§18). */
    @Query(
        """
        SELECT DISTINCT e.display_name AS label, s.date_added AS seenAt
        FROM graph_relations r
        JOIN graph_entities e ON e.id = r.entity_id AND e.type = 'PRICE'
        JOIN screenshots s ON s.id = r.screenshot_id
        WHERE r.screenshot_id IN (SELECT screenshot_id FROM graph_relations WHERE entity_id = :entityId)
        ORDER BY s.date_added ASC
        """
    )
    suspend fun pricesForEntityMembers(entityId: Long): List<EntityPriceRow>

    /** Websites linked from an entity's screenshots. */
    @Query(
        """
        SELECT DISTINCT e.display_name AS name
        FROM graph_relations r
        JOIN graph_entities e ON e.id = r.entity_id AND e.type = 'WEBSITE'
        WHERE r.screenshot_id IN (SELECT screenshot_id FROM graph_relations WHERE entity_id = :entityId)
        ORDER BY e.display_name ASC
        """
    )
    suspend fun websitesForEntityMembers(entityId: Long): List<String>

    /**
     * Categories filed on an entity's screenshots.
     *
     * `OTHER` is excluded: it is the classifier's "nothing matched" bucket, and
     * showing it as a category on an entity page states nothing. It is also
     * excluded when filing entities, so the two paths agree.
     */
    @Query(
        """
        SELECT DISTINCT c.category AS name
        FROM screenshot_categories c
        WHERE c.screenshot_id IN (SELECT screenshot_id FROM graph_relations WHERE entity_id = :entityId)
          AND c.source = 'auto'
          AND c.category <> 'OTHER'
        ORDER BY c.category ASC
        """
    )
    suspend fun categoriesForEntityMembers(entityId: Long): List<String>

    /**
     * Replaces one screenshot's graph footprint atomically: delete its edges,
     * file the new ones, drop entities nobody references anymore. Shared
     * entities survive because the orphan sweep only removes the unreferenced.
     */
    @Transaction
    suspend fun replaceScreenshotGraph(
        screenshotId: Long,
        entities: List<GraphEntityRow>,
        kinds: List<Pair<String, Double>>,
    ) {
        deleteRelationsFor(screenshotId)
        for ((index, entity) in entities.withIndex()) {
            val existing = entityByKey(entity.type, entity.normalizedName)
            val id = existing?.id ?: run {
                insertEntitiesIgnore(listOf(entity))
                entityByKey(entity.type, entity.normalizedName)?.id
            } ?: continue
            val (kind, confidence) = kinds[index]
            insertRelations(
                listOf(
                    GraphRelationRow(
                        screenshotId = screenshotId,
                        entityId = id,
                        kind = kind,
                        confidence = confidence,
                    ),
                ),
            )
        }
        deleteOrphanEntities()
    }
}

data class RelationWithEntity(
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "entityId") val entityId: Long,
    @ColumnInfo(name = "entityType") val entityType: String,
    @ColumnInfo(name = "displayName") val displayName: String,
)

data class EntityPriceRow(
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "seenAt") val seenAtSeconds: Long,
)

@Dao
interface CollectionDao {

    @Insert
    suspend fun insertCollection(collection: CollectionRow): Long

    @Query("SELECT * FROM collections ORDER BY created_at DESC")
    suspend fun allCollections(): List<CollectionRow>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun collectionById(id: Long): CollectionRow?

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMember(member: CollectionMemberRow)

    @Query("DELETE FROM collection_members WHERE collection_id = :collectionId AND screenshot_id = :screenshotId")
    suspend fun removeMember(collectionId: Long, screenshotId: Long)

    @Query(
        """
        SELECT m.screenshot_id FROM collection_members m
        JOIN screenshots s ON s.id = m.screenshot_id
        WHERE m.collection_id = :collectionId
        ORDER BY s.date_added DESC, s.id DESC
        """
    )
    suspend fun memberIds(collectionId: Long): List<Long>

    @Query("SELECT collection_id FROM collection_members WHERE screenshot_id = :screenshotId")
    suspend fun collectionsForScreenshot(screenshotId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM collection_members WHERE collection_id = :collectionId")
    suspend fun memberCount(collectionId: Long): Int
}
