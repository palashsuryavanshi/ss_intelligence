package com.ssintelligence.app.assistant

import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.graph.GraphEntityType
import com.ssintelligence.app.graph.GraphRepository
import com.ssintelligence.app.graph.EntityNormalization
import com.ssintelligence.app.search.SearchRequest
import com.ssintelligence.app.search.SearchResult
import com.ssintelligence.app.search.ScreenshotSearchEngine

/**
 * Runs the [QueryInterpreter]'s result through the existing Phase 1–4
 * retrieval systems and produces an ordered, explainable candidate set.
 *
 * Nothing here is new search machinery: it composes the FTS engine, the
 * semantic/visual hybrid, the knowledge graph, the timeline and collections.
 * Off-thread; never called from composition.
 */
class AssistantRetriever(
    private val searchEngine: ScreenshotSearchEngine,
    private val repository: ScreenshotRepository,
    private val graphRepository: GraphRepository,
) {

    /** One engine candidate enriched with which grounding signals fired. */
    data class Candidate(
        val result: SearchResult,
        val entityHit: Boolean,
        val temporalHit: Boolean,
        val graphHit: Boolean,
        /** 0-based position in the engine's own ordering; MAX_VALUE if not from the engine. */
        val engineRank: Int,
    )

    suspend fun retrieve(
        query: QueryInterpreter.InterpretedQuery,
        previousEvidenceIds: List<Long> = emptyList(),
    ): List<Candidate> {
        val engineQuery = query.keywords.joinToString(" ").ifBlank { query.entityText ?: "" }
        val engineResults = searchEngine.search(
            SearchRequest(query = engineQuery, limit = 60),
        ).results

        // When the structured search finds nothing — "otp" is classified as a
        // content-type filter on a table that may be empty — fall back to a
        // pure text search over the raw question. The user asking "What was the
        // OTP?" wants the screenshot containing the code, not a filter.
        val textResults = if (engineResults.isEmpty()) {
            searchEngine.textSearch(query.raw, limit = 60)
        } else {
            emptyList()
        }

        val candidates = (engineResults.mapIndexed { index, result ->
            Candidate(result, false, false, false, index)
        } + textResults.mapIndexed { index, result ->
            Candidate(result, false, false, false, engineResults.size + index)
        }).toMutableList()

        val entityText = query.entityText
        if (entityText != null) {
            val entityIds = resolveEntity(entityText)
                ?.let { graphRepository.screenshotIdsForEntity(it.id).toSet() }
                .orEmpty()
            val labels = graphRepository.entityLabelsFor(candidates.map { it.result.screenshot.id })
            for (i in candidates.indices) {
                val id = candidates[i].result.screenshot.id
                val inEntitySet = id in entityIds
                val graphNamed = labels[id].orEmpty().any { it.contains(entityText, ignoreCase = true) }
                candidates[i] = candidates[i].copy(entityHit = inEntitySet, graphHit = graphNamed)
            }
        }

        val temporal = query.dateRange
        if (temporal != null) {
            for (i in candidates.indices) {
                if (temporal.contains(candidates[i].result.screenshot.dateAdded)) {
                    candidates[i] = candidates[i].copy(temporalHit = true)
                }
            }
            // Pull timeline rows directly so "around X" works without keywords.
            val inWindow = repository.timeline(limit = 200).flatMap { it.screenshots }
                .filter { temporal.contains(it.dateAdded) }
                .filter { screen ->
                    val entityOk = entityText == null || runCatching {
                        graphRepository.entityLabelsFor(listOf(screen.id))[screen.id].orEmpty()
                            .any { it.contains(entityText, ignoreCase = true) }
                    }.getOrDefault(false)
                    entityOk && (query.keywords.isEmpty() || query.keywords.any { k ->
                        screen.ocrText.contains(k, ignoreCase = true)
                    })
                }
            for (screen in inWindow.take(30)) {
                if (candidates.none { it.result.screenshot.id == screen.id }) {
                    candidates.add(
                        Candidate(
                            SearchResult(screen, 0, null, emptyList()),
                            entityHit = entityText != null,
                            temporalHit = true,
                            graphHit = false,
                            engineRank = Int.MAX_VALUE,
                        ),
                    )
                }
            }
        }

        // A follow-up with no text match ("Which had the lowest price?") reuses
        // the previous turn's evidence: the user is asking about the screenshots
        // just found, not searching the library again. Without this, every
        // pronoun-only follow-up would answer "I couldn't find…".
        if (candidates.isEmpty() && previousEvidenceIds.isNotEmpty()) {
            val shots = repository.getByIds(previousEvidenceIds)
            return shots.map { shot ->
                Candidate(
                    SearchResult(shot, 0, null, emptyList()),
                    entityHit = false,
                    temporalHit = false,
                    graphHit = false,
                    engineRank = Int.MAX_VALUE,
                )
            }
        }

        return candidates.distinctBy { it.result.screenshot.id }
    }

    /** Resolves free text to a graph entity across the plausible types. */
    private suspend fun resolveEntity(text: String): com.ssintelligence.app.graph.GraphEntity? {
        val normalizedProduct = EntityNormalization.product(text)
        val normalizedHost = EntityNormalization.website(text)
        for (type in listOf(GraphEntityType.PRODUCT, GraphEntityType.WEBSITE, GraphEntityType.COMPANY)) {
            val key = if (type == GraphEntityType.PRODUCT) normalizedProduct else normalizedHost
            graphRepository.resolveEntity(type, key)?.let { return it }
        }
        return null
    }
}
