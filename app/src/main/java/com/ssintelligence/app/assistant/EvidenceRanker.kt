package com.ssintelligence.app.assistant

/**
 * Combines the heterogeneous retrieval signals into one normalized ordering.
 *
 * Each signal is mapped to [0, 1] before combining: the engine's own relevance
 * order (1 for the first, decaying), a boolean entity hit, a temporal window
 * hit, and a graph-entity match. Weights are internal and configurable, never
 * displayed; the user sees match reasons, not numbers.
 */
class EvidenceRanker(
    private val weights: Weights = Weights(),
) {
    data class Weights(
        val engine: Double = 0.5,
        val entity: Double = 0.25,
        val temporal: Double = 0.15,
        val graph: Double = 0.10,
    )

    data class Scored(val candidate: AssistantRetriever.Candidate, val score: Double)

    /** Normalized combined score in [0, 1], descending list. */
    fun rank(candidates: List<AssistantRetriever.Candidate>): List<Scored> {
        val maxRank = candidates.size.coerceAtLeast(1)
        return candidates.map { candidate ->
            val engineScore = if (candidate.engineRank == Int.MAX_VALUE) {
                0.0
            } else {
                1.0 - (candidate.engineRank.toDouble() / maxRank)
            }
            val entityPart = if (candidate.entityHit) weights.entity else 0.0
            val temporalPart = if (candidate.temporalHit) weights.temporal else 0.0
            val graphPart = if (candidate.graphHit) weights.graph else 0.0
            val score = weights.engine * engineScore + entityPart + temporalPart + graphPart
            Scored(candidate, score.coerceIn(0.0, 1.0))
        }.sortedByDescending { it.score }
    }
}
