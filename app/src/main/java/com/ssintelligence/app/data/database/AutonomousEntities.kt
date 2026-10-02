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
import kotlinx.coroutines.flow.Flow

/**
 * Phase 6 autonomous organization storage (§68).
 *
 * Every table references screenshots by id and stores only derived intelligence —
 * never image bytes. All of it is regeneratable: clearing it never touches the
 * screenshot index, and a rebuild reproduces it from the same inputs.
 */

@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"])],
)
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "signals") val signals: String,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"])],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "start_seconds") val startSeconds: Long,
    @ColumnInfo(name = "end_seconds") val endSeconds: Long,
    @ColumnInfo(name = "signals") val signals: String,
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"])],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "confidence") val confidence: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "start_seconds") val startSeconds: Long,
    @ColumnInfo(name = "end_seconds") val endSeconds: Long,
    @ColumnInfo(name = "signals") val signals: String,
)

@Entity(
    tableName = "screenshot_tags",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"]), Index(value = ["label"])],
)
data class ScreenshotTagEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "source") val source: String,
    /** False once the user removes the tag. */
    @ColumnInfo(name = "removed") val removed: Boolean = false,
)

@Entity(
    tableName = "suggestions",
    indices = [Index(value = ["kind"]), Index(value = ["screenshot_id"])],
)
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "accepted") val accepted: Boolean = false,
    @ColumnInfo(name = "dismissed") val dismissed: Boolean = false,
)

@Entity(
    tableName = "archive_state",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"], childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"], unique = true)],
)
data class ArchiveStateEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "archived") val archived: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface AutonomousDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopic(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: ScreenshotTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestion(suggestion: SuggestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setArchiveState(state: ArchiveStateEntity)

    @Query("SELECT * FROM topics WHERE screenshot_id = :id")
    suspend fun topicFor(id: Long): TopicEntity?

    @Query("SELECT * FROM topics ORDER BY id DESC")
    suspend fun observeTopics(): List<TopicEntity>

    @Query("SELECT * FROM sessions WHERE screenshot_id = :id")
    suspend fun sessionFor(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY id DESC")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM events WHERE screenshot_id = :id")
    suspend fun eventFor(id: Long): EventEntity?

    @Query("SELECT * FROM events ORDER BY id DESC")
    suspend fun allEvents(): List<EventEntity>

    @Query("SELECT * FROM screenshot_tags WHERE screenshot_id = :id AND removed = 0")
    suspend fun tagsFor(id: Long): List<ScreenshotTagEntity>

    @Query("SELECT * FROM screenshot_tags WHERE removed = 0 ORDER BY label")
    fun observeTags(): Flow<List<ScreenshotTagEntity>>

    @Query("SELECT * FROM suggestions WHERE dismissed = 0 ORDER BY id DESC")
    fun observeSuggestions(): Flow<List<SuggestionEntity>>

    @Query("SELECT * FROM suggestions WHERE id = :id")
    suspend fun suggestionById(id: Long): SuggestionEntity?

    @Query("UPDATE suggestions SET accepted = 1 WHERE id = :id")
    suspend fun acceptSuggestion(id: Long)

    @Query("UPDATE suggestions SET dismissed = 1 WHERE id = :id")
    suspend fun dismissSuggestion(id: Long)

    @Query("SELECT * FROM archive_state WHERE screenshot_id = :id")
    suspend fun archiveStateFor(id: Long): ArchiveStateEntity?

    @Query("UPDATE archive_state SET archived = :archived, updated_at = :now WHERE screenshot_id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query("DELETE FROM topics")
    suspend fun clearTopics()

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM events")
    suspend fun clearEvents()

    @Query("DELETE FROM screenshot_tags")
    suspend fun clearTags()

    @Query("DELETE FROM suggestions")
    suspend fun clearSuggestions()

    @Query("DELETE FROM archive_state")
    suspend fun clearArchiveState()
}
