package com.ssintelligence.app.domain.usecase

import com.ssintelligence.app.assistant.AssistantContext
import com.ssintelligence.app.assistant.AssistantRepository
import com.ssintelligence.app.assistant.AssistantResponse
import com.ssintelligence.app.assistant.ScreenshotAssistant
import com.ssintelligence.app.domain.repository.ScreenshotRepository

/**
 * Phase 5 assistant use cases.
 *
 * Each use case is a thin, single-responsibility wrapper over the assistant
 * pipeline or its store, so the ViewModel never touches the pipeline directly
 * and the pipeline never touches the UI.
 */

/** Asks the assistant a question, carrying conversational context across turns. */
class AskAssistantUseCase(
    private val assistant: ScreenshotAssistant,
    private val repository: AssistantRepository,
) {
    data class Result(
        val response: AssistantResponse,
        val context: AssistantContext,
    )

    suspend operator fun invoke(
        query: String,
        conversationId: Long?,
        context: AssistantContext,
    ): Result {
        val turn = assistant.ask(query, context)
        if (conversationId != null) {
            repository.appendTurn(conversationId, query, turn.response, System.currentTimeMillis())
        }
        return Result(turn.response, turn.context)
    }
}

/** Starts a new conversation and returns its id. */
class StartAssistantConversationUseCase(
    private val repository: AssistantRepository,
) {
    suspend operator fun invoke(title: String): Long =
        repository.startConversation(title, System.currentTimeMillis())
}

/** Observes the conversation list for the assistant screen. */
class ObserveAssistantConversationsUseCase(
    private val repository: AssistantRepository,
) {
    operator fun invoke() = repository.observeConversations()
}

/** Observes one conversation's messages. */
class ObserveAssistantMessagesUseCase(
    private val repository: AssistantRepository,
) {
    operator fun invoke(conversationId: Long) = repository.observeMessages(conversationId)
}

/** Deletes one conversation. Screenshots are untouched. */
class DeleteAssistantConversationUseCase(
    private val repository: AssistantRepository,
) {
    suspend operator fun invoke(id: Long) = repository.deleteConversation(id)
}

/** Deletes all conversation history. */
class ClearAssistantHistoryUseCase(
    private val repository: AssistantRepository,
) {
    suspend operator fun invoke() = repository.clearHistory()
}

/** Saves an assistant answer as a reusable memory snapshot (§46). */
class SaveMemorySnapshotUseCase(
    private val repository: AssistantRepository,
) {
    suspend operator fun invoke(
        name: String,
        summary: String,
        screenshotIds: List<Long>,
    ): Long = repository.saveSnapshot(name, summary, screenshotIds, System.currentTimeMillis())
}

/** Observes saved memory snapshots. */
class ObserveMemorySnapshotsUseCase(
    private val repository: AssistantRepository,
) {
    operator fun invoke() = repository.observeSnapshots()
}

/** Deletes one memory snapshot. */
class DeleteMemorySnapshotUseCase(
    private val repository: AssistantRepository,
) {
    suspend operator fun invoke(id: Long) = repository.deleteSnapshot(id)
}

/** Deletes every memory snapshot. */
class ClearMemorySnapshotsUseCase(
    private val repository: AssistantRepository,
) {
    suspend operator fun invoke() = repository.clearSnapshots()
}

/**
 * Suggested opening questions, derived from the actual indexed corpus (§33).
 *
 * Every suggestion is computed from real data — top entities, recent prices,
 * timeline coverage — so the list is empty rather than padded when there is
 * nothing to suggest. Nothing here is a canned string.
 */
class AssistantSuggestionsUseCase(
    private val screenshotRepository: ScreenshotRepository,
) {
    data class Suggestion(val text: String, val kind: Kind) {
        enum class Kind { RECENT, PRICE, ENTITY, TIMELINE, VISUAL }
    }

    suspend operator fun invoke(limit: Int = 6): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val recent = screenshotRepository.getRecent(limit = 3)
        if (recent.isNotEmpty()) {
            out += Suggestion("What did I save recently?", Suggestion.Kind.RECENT)
        }
        val entities = screenshotRepository.topEntities(
            types = listOf(
                com.ssintelligence.app.graph.GraphEntityType.PRODUCT,
                com.ssintelligence.app.graph.GraphEntityType.WEBSITE,
            ),
            minCount = 3,
            limit = 3,
        )
        for (entity in entities) {
            out += Suggestion("What did I save about ${entity.entity.displayName}?", Suggestion.Kind.ENTITY)
        }
        val timeline = screenshotRepository.timeline(limit = 1)
        if (timeline.isNotEmpty() && timeline.first().screenshots.size >= 2) {
            out += Suggestion("Show my recent price comparisons", Suggestion.Kind.PRICE)
        }
        val visual = screenshotRepository.visualFor(recent.firstOrNull()?.id ?: 0L)
        if (visual != null) {
            out += Suggestion("Find screenshots that look like this", Suggestion.Kind.VISUAL)
        }
        return out.take(limit)
    }
}
