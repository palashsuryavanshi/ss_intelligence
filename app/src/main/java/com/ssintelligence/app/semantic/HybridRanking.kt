package com.ssintelligence.app.semantic

/**
 * Hybrid lexical + semantic + metadata scoring (§13, §14).
 *
 * Three normalized 0..100 signals combine by weight. The weights are
 * configurable because the 45/35/20 starting point is an engineering guess to
 * be tuned against the benchmark, not a finding.
 *
 * The exact-match guarantee (§14) falls out of the arithmetic rather than a
 * special case: a screenshot matching every lexical term and every structured
 * filter scores ~65 before semantics is even counted, while a semantic-only
 * match caps at 35. No embedding, however close, can outrank an exact match —
 * because 35 is less than 65, always. If the weights are ever re-tuned, the
 * [exactMatchFloorHolds] test fails rather than the guarantee silently dying.
 */
data class HybridWeights(
    val lexical: Double = 0.45,
    val semantic: Double = 0.35,
    val metadata: Double = 0.20,
) {
    init {
        require(lexical >= 0 && semantic >= 0 && metadata >= 0) { "Weights must be non-negative" }
        require(lexical + semantic + metadata > 0) { "At least one weight must be positive" }
    }

    companion object {
        val Default = HybridWeights()
    }
}

/** The three signals for one candidate, each 0..100. */
data class HybridSignals(
    /** Phase 2 ranker score, normalized. */
    val lexical: Double,
    /** Best cosine similarity to the query embedding, 0..1. */
    val semantic: Double,
    /** Share of structured constraints satisfied, 0..1. */
    val metadata: Double,
)

class HybridRanker(
    private val weights: HybridWeights = HybridWeights.Default,
) {

    /** Weighted final score, 0..100. Higher is more relevant. */
    fun score(signals: HybridSignals): Double {
        val total = weights.lexical + weights.semantic + weights.metadata
        return (
            signals.lexical.coerceIn(0.0, 100.0) * weights.lexical +
                signals.semantic.coerceIn(0.0, 1.0) * 100.0 * weights.semantic +
                signals.metadata.coerceIn(0.0, 1.0) * 100.0 * weights.metadata
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
