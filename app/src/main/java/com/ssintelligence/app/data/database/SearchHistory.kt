package com.ssintelligence.app.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Locally stored search queries (§24, §37).
 *
 * This is the most sensitive table in the app: a query is whatever the user
 * chose to type, which is often the one string they would least want copied
 * anywhere. Three properties are structural rather than promised:
 *
 * 1. **Opt-in.** Nothing is written unless the user turned search history on
 *    in Settings. The default is off, and off means nothing is stored at all —
 *    not "stored and hidden".
 * 2. **Query and timestamp only.** No result ids, no screenshots, no matched
 *    text, so a history row cannot be used to reconstruct what was found.
 * 3. **Bounded and erasable.** The table is trimmed to [DEFAULT_LIMIT] rows on
 *    every write and can be deleted outright from Settings.
 *
 * Codes are never written here even when history is enabled: see
 * [com.ssintelligence.app.search.parser.OtpQueryParser]. A history row is
 * shown in a list, and a list is exactly where a one-time code would leak.
 */
@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["created_at"]),
        // Re-searching the same thing updates the existing row instead of
        // growing an unbounded list of near-duplicates.
        Index(value = ["query"], unique = true),
    ],
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Upsert
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query(
        """
        DELETE FROM search_history
        WHERE id NOT IN (
            SELECT id FROM search_history
            ORDER BY created_at DESC, id DESC
            LIMIT :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)

    /**
     * Records one query and enforces the cap in a single transaction, so the
     * table can never exceed the limit even if two searches race.
     */
    @Transaction
    suspend fun record(query: String, now: Long, keep: Int = DEFAULT_LIMIT) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val existing = recent(keep)
        val duplicate = existing.firstOrNull { it.query.equals(trimmed, ignoreCase = true) }
        upsert(
            SearchHistoryEntity(
                id = duplicate?.id ?: 0L,
                query = trimmed,
                createdAt = now,
            )
        )
        trimTo(keep)
    }

    companion object {
        /**
         * Default cap. Twenty-five is enough to be useful for recall and small
         * enough that the table is never a meaningful record of what the user
         * has been looking for.
         */
        const val DEFAULT_LIMIT = 25
    }
}
