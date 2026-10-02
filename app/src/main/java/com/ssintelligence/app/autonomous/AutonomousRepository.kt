package com.ssintelligence.app.autonomous

import com.ssintelligence.app.data.database.AutonomousDao
import com.ssintelligence.app.data.database.EventEntity
import com.ssintelligence.app.data.database.SessionEntity
import com.ssintelligence.app.data.database.SuggestionEntity
import com.ssintelligence.app.data.database.TopicEntity
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.semantic.ScreenshotDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The autonomous organization repository.
 *
 * Runs the [AutonomousAnalysisEngine] over screenshots the Phase 1-5 pipeline
 * already indexed, and exposes the derived topics, sessions, events, tags,
 * suggestions and archive state to the UI. Every write is a reference or a
 * label; no screenshot is ever modified or deleted here.
 */
interface AutonomousRepository {

    /** Analyzes one screenshot and stores what the engine derived. */
    suspend fun analyze(
        screenshot: Screenshot,
        document: ScreenshotDocument,
        entities: Set<String>,
        visualType: String?,
        visualLayout: String?,
        isDark: Boolean,
        colors: List<String>,
        neighbours: List<AutonomousAnalysisEngine.Input.Neighbour>,
    )

    suspend fun topics(): List<Topic>
    suspend fun sessions(): List<ScreenshotSession>
    suspend fun events(): List<ScreenshotEvent>
    suspend fun tagsFor(screenshotId: Long): List<SmartTag>
    fun observeTags(): Flow<List<SmartTag>>
    fun observeSuggestions(): Flow<List<Suggestion>>
    suspend fun acceptSuggestion(id: Long)
    suspend fun dismissSuggestion(id: Long)
    suspend fun setArchived(screenshotId: Long, archived: Boolean)
    suspend fun isArchived(screenshotId: Long): Boolean
    suspend fun clearAutonomousData()
}

class AutonomousRepositoryImpl(
    private val dao: AutonomousDao,
    private val engine: AutonomousAnalysisEngine = AutonomousAnalysisEngine(),
) : AutonomousRepository {

    override suspend fun analyze(
        screenshot: Screenshot,
        document: ScreenshotDocument,
        entities: Set<String>,
        visualType: String?,
        visualLayout: String?,
        isDark: Boolean,
        colors: List<String>,
        neighbours: List<AutonomousAnalysisEngine.Input.Neighbour>,
    ) {
        val input = AutonomousAnalysisEngine.Input(
            screenshot = screenshot,
            document = document,
            entities = entities,
            visualType = visualType,
            visualLayout = visualLayout,
            isDark = isDark,
            colors = colors,
            neighbours = neighbours,
        )
        val now = System.currentTimeMillis() / 1000

        val topic = engine.detectTopic(input)
        dao.insertTopic(
            TopicEntity(
                screenshotId = screenshot.id,
                label = topic.label,
                subject = topic.subject,
                signals = topic.signals.joinToString(";"),
            ),
        )

        engine.detectSession(input)?.let { session ->
            dao.insertSession(
                SessionEntity(
                    screenshotId = screenshot.id,
                    label = session.label,
                    subject = session.subject,
                    startSeconds = session.startSeconds,
                    endSeconds = session.endSeconds,
                    signals = session.signals.joinToString(";"),
                ),
            )
        }

        engine.detectEvent(input)?.let { event ->
            dao.insertEvent(
                EventEntity(
                    screenshotId = screenshot.id,
                    type = event.type.name,
                    confidence = event.confidence.name,
                    subject = event.subject,
                    startSeconds = event.startSeconds ?: 0L,
                    endSeconds = event.endSeconds ?: 0L,
                    signals = event.signals.joinToString(";"),
                ),
            )
        }

        // Tags: one per entity, plus a lifecycle tag. User-removable.
        for (entity in entities.take(4)) {
            dao.insertTag(
                com.ssintelligence.app.data.database.ScreenshotTagEntity(
                    screenshotId = screenshot.id,
                    label = entity.lowercase(),
                    source = "entity",
                ),
            )
        }
        val lifecycle = engine.analyzeLifecycle(input, now)
        dao.insertTag(
            com.ssintelligence.app.data.database.ScreenshotTagEntity(
                screenshotId = screenshot.id,
                label = lifecycle.name.lowercase(),
                source = "lifecycle",
            ),
        )

        for (suggestion in engine.suggest(input, now)) {
            dao.insertSuggestion(
                SuggestionEntity(
                    kind = suggestion.kind.name,
                    screenshotId = screenshot.id,
                    reason = suggestion.reason,
                ),
            )
        }
    }

    override suspend fun topics(): List<Topic> = dao.observeTopics().map { row ->
        Topic(
            id = "topic-${row.screenshotId}",
            label = row.label,
            screenshotIds = listOf(row.screenshotId),
            subject = row.subject,
            signals = row.signals.split(";").filter { it.isNotBlank() },
        )
    }

    override suspend fun sessions(): List<ScreenshotSession> = dao.allSessions().map { row ->
        ScreenshotSession(
            id = "session-${row.screenshotId}",
            label = row.label,
            screenshotIds = listOf(row.screenshotId),
            startSeconds = row.startSeconds,
            endSeconds = row.endSeconds,
            subject = row.subject,
            signals = row.signals.split(";").filter { it.isNotBlank() },
        )
    }

    override suspend fun events(): List<ScreenshotEvent> = dao.allEvents().map { row ->
        ScreenshotEvent(
            id = "event-${row.screenshotId}",
            type = runCatching { EventType.valueOf(row.type) }.getOrNull() ?: EventType.OTHER,
            confidence = runCatching { EventConfidence.valueOf(row.confidence) }.getOrNull()
                ?: EventConfidence.NEEDS_REVIEW,
            screenshotIds = listOf(row.screenshotId),
            startSeconds = row.startSeconds,
            endSeconds = row.endSeconds,
            subject = row.subject,
            signals = row.signals.split(";").filter { it.isNotBlank() },
        )
    }

    override suspend fun tagsFor(screenshotId: Long): List<SmartTag> =
        dao.tagsFor(screenshotId).map { SmartTag(it.screenshotId, it.label, it.source) }

    override fun observeTags(): Flow<List<SmartTag>> =
        dao.observeTags().map { rows -> rows.map { SmartTag(it.screenshotId, it.label, it.source) } }

    override fun observeSuggestions(): Flow<List<Suggestion>> =
        dao.observeSuggestions().map { rows ->
            rows.map {
                Suggestion(
                    id = it.id,
                    kind = runCatching { SuggestionKind.valueOf(it.kind) }.getOrNull()
                        ?: SuggestionKind.UNORGANIZED,
                    screenshotIds = listOf(it.screenshotId),
                    reason = it.reason,
                    accepted = it.accepted,
                )
            }
        }

    override suspend fun acceptSuggestion(id: Long) {
        dao.acceptSuggestion(id)
    }

    override suspend fun dismissSuggestion(id: Long) {
        dao.dismissSuggestion(id)
    }

    override suspend fun setArchived(screenshotId: Long, archived: Boolean) {
        dao.setArchived(screenshotId, archived, System.currentTimeMillis() / 1000)
    }

    override suspend fun isArchived(screenshotId: Long): Boolean =
        dao.archiveStateFor(screenshotId)?.archived ?: false

    override suspend fun clearAutonomousData() {
        dao.clearTopics()
        dao.clearSessions()
        dao.clearEvents()
        dao.clearTags()
        dao.clearSuggestions()
        dao.clearArchiveState()
    }
}
