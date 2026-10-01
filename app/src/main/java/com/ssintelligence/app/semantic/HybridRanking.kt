package com.ssintelligence.app.semantic

/**
 * Hybrid lexical + semantic + metadata scoring (§13 Phase 3, §48 Phase 4).
 *
 * Five normalized signals combine by weight. The weights are configurable
 * because every starting point here is an engineering guess to be tuned
 * against the benchmark, not a finding.
 *
 * The exact-match guarantee (§14, §49) falls out of the arithmetic rather than
 * a special case: a screenshot matching every lexical term and every
 * structured filter scores 60 before the other signals are even counted, while
 * semantic-only, visual-only and entity-only matches cap at 40 combined. No
 * embedding, however close, can outrank an exact match — because 40 is less
 * than 60, always. If the weights are ever re-tuned, the [exactMatchFloorHolds]
 * test fails rather than the guarantee silently dying.
 */
data class HybridWeights(
    val lexical: Double = 0.40,
    val semantic: Double = 0.25,
    val metadata: Double = 0.20,
    /** Visual similarity: palette overlap or image-hash closeness. */
    val visual: Double = 0.10,
    /** Share of the query's entities found on the candidate. */
    val entity: Double = 0.05,
) {
    init {
        require(lexical >= 0 && semantic >= 0 && metadata >= 0 && visual >= 0 && entity >= 0) {
            "Weights must be non-negative"
        }
        require(lexical + semantic + metadata + visual + entity > 0) {
            "At least one weight must be positive"
        }
    }

    companion object {
        val Default = HybridWeights()
    }
}

/** The signals for one candidate: lexical 0..100, the rest 0..1. */
data class HybridSignals(
    /** Phase 2 ranker score, normalized. */
    val lexical: Double,
    /** Best cosine similarity to the query embedding, 0..1. */
    val semantic: Double,
    /** Share of structured constraints satisfied, 0..1. */
    val metadata: Double,
    /** Visual similarity: palette overlap or dHash closeness, 0..1. */
    val visual: Double = 0.0,
    /** Share of query entities present on the candidate, 0..1. */
    val entity: Double = 0.0,
)

class HybridRanker(
    private val weights: HybridWeights = HybridWeights.Default,
) {

    /** Weighted final score, 0..100. Higher is more relevant. */
    fun score(signals: HybridSignals): Double {
        val total = weights.lexical + weights.semantic + weights.metadata +
            weights.visual + weights.entity
        return (
            signals.lexical.coerceIn(0.0, 100.0) * weights.lexical +
                signals.semantic.coerceIn(0.0, 1.0) * 100.0 * weights.semantic +
                signals.metadata.coerceIn(0.0, 1.0) * 100.0 * weights.metadata +
                signals.visual.coerceIn(0.0, 1.0) * 100.0 * weights.visual +
                signals.entity.coerceIn(0.0, 1.0) * 100.0 * weights.entity
            ) / total
    }

    /**
     * Normalizes a Phase 2 ranker score to 0..100.
     *
     * The Phase 2 scale tops out around phrase (100) + all-terms (60) + price
     * (50) + URL (40) + recency (6). Dividing by [PHASE2_CEILING] and capping
     * keeps an exceptional exact match at 100 without letting the number drift
     * every time a weight is tuned.
     */
    fun normalizeLexical(phase2Score: Int): Double =
        (phase2Score / PHASE2_CEILING).coerceIn(0.0, 1.0) * 100.0

    companion object {
        private const val PHASE2_CEILING = 256.0
    }
}

/** One vector-search hit (§9). */
data class SemanticMatch(
    val screenshotId: Long,
    /** Cosine similarity, 0..1. Never shown to users (§11). */
    val similarity: Double,
)
