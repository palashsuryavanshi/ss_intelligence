package com.ssintelligence.app.assistant

import com.ssintelligence.app.data.database.AssistantConversationEntity
import com.ssintelligence.app.data.database.AssistantDao
import com.ssintelligence.app.data.database.AssistantEvidenceEntity
import com.ssintelligence.app.data.database.AssistantMessageEntity
import com.ssintelligence.app.data.database.MemorySnapshotEntity
import com.ssintelligence.app.data.database.MemorySnapshotItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Local conversation and memory-snapshot store (§13, §46, §47).
 *
 * Conversations are user data, not screenshot data: they are never folded into
 * the screenshot index, never used to rank search, and can be deleted
 * wholesale from Settings. Only ids and text are stored — no image bytes, no
 * embeddings.
 */
interface AssistantRepository {

    fun observeConversations(): Flow<List<AssistantConversationEntity>>

    fun observeMessages(conversationId: Long): Flow<List<AssistantMessageEntity>>

    suspend fun conversationCount(): Int

    /** Starts a conversation and returns its id. */
    suspend fun startConversation(title: String, now: Long): Long

    /** Appends a user/assistant pair and files the evidence trail. */
    suspend fun appendTurn(
        conversationId: Long,
        userText: String,
        response: AssistantResponse,
        now: Long,
    )

    suspend fun deleteConversation(id: Long)

    /** Deletes every conversation and message. Screenshots are untouched. */
    suspend fun clearHistory()

    // ------------------------------------------------------ memory snapshots

    fun observeSnapshots(): Flow<List<MemorySnapshotEntity>>

    suspend fun saveSnapshot(name: String, summary: String, screenshotIds: List<Long>, now: Long): Long

    suspend fun snapshotItems(id: Long): List<Long>

    suspend fun deleteSnapshot(id: Long)

    suspend fun clearSnapshots()
}

class AssistantRepositoryImpl(
    private val dao: AssistantDao,
) : AssistantRepository {

    override fun observeConversations(): Flow<List<AssistantConversationEntity>> =
        dao.observeConversations()

    override fun observeMessages(conversationId: Long): Flow<List<AssistantMessageEntity>> =
        dao.observeMessages(conversationId)

    override suspend fun conversationCount(): Int = dao.conversationCount()

    override suspend fun startConversation(title: String, now: Long): Long =
        dao.insertConversation(
            AssistantConversationEntity(
                title = title.take(80).ifBlank { "Conversation" },
                createdAt = now,
                updatedAt = now,
            ),
        )

    override suspend fun appendTurn(
        conversationId: Long,
        userText: String,
        response: AssistantResponse,
        now: Long,
    ) {
        val messageId = dao.insertMessage(
            AssistantMessageEntity(
                conversationId = conversationId,
                role = "user",
                text = userText,
                intent = null,
                createdAt = now,
            ),
        )
        val assistantMessageId = dao.insertMessage(
            AssistantMessageEntity(
                conversationId = conversationId,
                role = "assistant",
                text = response.answer,
                intent = response.intent.name,
                createdAt = now + 1,
            ),
        )
        dao.insertEvidence(
            response.sources.mapIndexed { index, source ->
                AssistantEvidenceEntity(
                    messageId = assistantMessageId,
                    screenshotId = source.screenshotId,
                    kind = response.evidenceChain.signalSummary.getOrNull(index) ?: "source",
                )
            },
        )
        // Touch the conversation so it sorts to the top.
        dao.insertConversation(
            AssistantConversationEntity(
                id = conversationId,
                title = userText.take(80).ifBlank { "Conversation" },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun deleteConversation(id: Long) {
        dao.deleteConversation(id)
    }

    override suspend fun clearHistory() {
        dao.clearMessages()
        dao.clearConversations()
    }

    override fun observeSnapshots(): Flow<List<MemorySnapshotEntity>> = dao.observeSnapshots()

    override suspend fun saveSnapshot(
        name: String,
        summary: String,
        screenshotIds: List<Long>,
        now: Long,
    ): Long {
        val id = dao.insertSnapshot(
            MemorySnapshotEntity(
                name = name.take(80).ifBlank { "Memory" },
                summary = summary,
                createdAt = now,
            ),
        )
        dao.insertSnapshotItems(
            screenshotIds.distinct().map {
                MemorySnapshotItemEntity(snapshotId = id, screenshotId = it)
            },
        )
        return id
    }

    override suspend fun snapshotItems(id: Long): List<Long> =
        dao.snapshotItems(id).map { it.screenshotId }

    override suspend fun deleteSnapshot(id: Long) {
        dao.deleteSnapshot(id)
    }

    override suspend fun clearSnapshots() {
        dao.clearSnapshotItems()
        dao.clearSnapshots()
    }
}
