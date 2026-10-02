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
 * Phase 5 assistant storage (§56).
 *
 * Kept deliberately small: conversations and messages reference screenshots
 * by id, and evidence rows only carry the screenshot's identity and the kind
 * of match. Images are never copied; a user can always reopen the original
 * from MediaStore.
 */
@Entity(
    tableName = "assistant_conversations",
    indices = [Index(value = ["updated_at"])],
)
data class AssistantConversationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "assistant_messages",
    foreignKeys = [
        ForeignKey(
            entity = AssistantConversationEntity::class,
            parentColumns = ["id"], childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id"])],
)
data class AssistantMessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "role") val role: String, // "user" | "assistant"
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "intent") val intent: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "assistant_evidence",
    foreignKeys = [
        ForeignKey(
            entity = AssistantMessageEntity::class,
            parentColumns = ["id"], childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["message_id"]), Index(value = ["screenshot_id"])],
)
data class AssistantEvidenceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
    @ColumnInfo(name = "kind") val kind: String,
)

@Entity(tableName = "memory_snapshots", indices = [Index(value = ["created_at"])])
data class MemorySnapshotEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "memory_snapshot_items",
    foreignKeys = [
        ForeignKey(
            entity = MemorySnapshotEntity::class,
            parentColumns = ["id"], childColumns = ["snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["snapshot_id"]), Index(value = ["screenshot_id"])],
)
data class MemorySnapshotItemEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "snapshot_id") val snapshotId: Long,
    @ColumnInfo(name = "screenshot_id") val screenshotId: Long,
)

@Dao
interface AssistantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: AssistantConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AssistantMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: List<AssistantEvidenceEntity>)

    @Query("SELECT * FROM assistant_conversations ORDER BY updated_at DESC")
    fun observeConversations(): Flow<List<AssistantConversationEntity>>

    @Query("SELECT * FROM assistant_messages WHERE conversation_id = :id ORDER BY created_at, id")
    fun observeMessages(id: Long): Flow<List<AssistantMessageEntity>>

    @Query("SELECT * FROM assistant_evidence WHERE message_id = :id")
    suspend fun evidenceFor(id: Long): List<AssistantEvidenceEntity>

    @Query("DELETE FROM assistant_conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM assistant_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM assistant_conversations")
    suspend fun clearConversations()

    @Query("SELECT COUNT(*) FROM assistant_conversations")
    suspend fun conversationCount(): Int

    // --------------- memory snapshots

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: MemorySnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshotItems(items: List<MemorySnapshotItemEntity>)

    @Query("SELECT * FROM memory_snapshots ORDER BY created_at DESC")
    fun observeSnapshots(): Flow<List<MemorySnapshotEntity>>

    @Query("SELECT * FROM memory_snapshot_items WHERE snapshot_id = :id")
    suspend fun snapshotItems(id: Long): List<MemorySnapshotItemEntity>

    @Query("DELETE FROM memory_snapshots WHERE id = :id")
    suspend fun deleteSnapshot(id: Long)

    @Query("DELETE FROM memory_snapshot_items")
    suspend fun clearSnapshotItems()

    @Query("DELETE FROM memory_snapshots")
    suspend fun clearSnapshots()
}
