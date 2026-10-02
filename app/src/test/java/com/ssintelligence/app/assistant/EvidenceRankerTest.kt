package com.ssintelligence.app.assistant

import com.ssintelligence.app.search.SearchResult
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.ProcessingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Normalized, configurable signal combination. */
class EvidenceRankerTest {

    private fun screenshot(id: Long) = Screenshot(
        id = id, mediaStoreId = id, uri = "content://x/$id", filename = "s$id.png",
        relativePath = null, dateAdded = 1_700_000_000L + id, dateModified = 0L, fileSize = 1,
        width = 1080, height = 2400, mimeType = "image/png", ocrText = "", contentHash = "h$id",
        duplicateOfId = null, status = ProcessingStatus.COMPLETED, error = null,
        createdAt = 0, updatedAt = 0,
    )

    private fun candidate(id: Long, rank: Int, entity: Boolean = false, temporal: Boolean = false, graph: Boolean = false) =
        AssistantRetriever.Candidate(
            result = SearchResult(screenshot(id), score = 0, snippet = null, matches = emptyList()),
            entityHit = entity, temporalHit = temporal, graphHit = graph, engineRank = rank,
        )

    @Test
    fun `the engine order dominates by default`() {
        val ranked = EvidenceRanker().rank(
            listOf(candidate(1, 0), candidate(2, 1), candidate(3, 2)),
        )
        assertEquals(listOf(1L, 2L, 3L), ranked.map { it.candidate.result.screenshot.id })
    }

    @Test
    fun `an entity hit can promote a lower ranked row`() {
        val ranked = EvidenceRanker().rank(
            listOf(candidate(1, 0), candidate(2, 1, entity = true)),
        )
        // Default weights make this a tie (0.5 each); the engine order breaks it.
        assertEquals(1L, ranked.first().candidate.result.screenshot.id)
        assertEquals(ranked[0].score, ranked[1].score, 0.001)
    }

    @Test
    fun `entity heavy weights promote the grounded row`() {
        val ranker = EvidenceRanker(EvidenceRanker.Weights(engine = 0.1, entity = 0.8, temporal = 0.05, graph = 0.05))
        val ranked = ranker.rank(
            listOf(candidate(1, 0), candidate(2, 5, entity = true)),
        )
        assertEquals(2L, ranked.first().candidate.result.screenshot.id)
    }

    @Test
    fun `scores stay normalized`() {
        val ranked = EvidenceRanker().rank(
            listOf(
                candidate(1, 0, entity = true, temporal = true, graph = true),
                candidate(2, 1),
            ),
        )
        assertTrue(ranked.all { it.score in 0.0..1.0 })
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `a non-engine candidate can still rank on grounding alone`() {
        val ranked = EvidenceRanker().rank(
            listOf(
                candidate(1, 0),
                candidate(2, Int.MAX_VALUE, entity = true, temporal = true),
            ),
        )
        // Row 1 keeps the engine signal; row 2 is grounded but unranked.
        assertEquals(1L, ranked.first().candidate.result.screenshot.id)
    }
}
