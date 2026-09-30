package com.ssintelligence.app.search

/**
 * Seam for local semantic search (Phase 3), deliberately inert for now (§34).
 *
 * The interface exists so the retrieval pipeline has a second, optional input
 * alongside structured search, and so result fusion is already a distinct step
 * rather than something bolted on later:
 *
 * ```
 * User Query → Query Parser → Structured Search ─┐
 *                                               ├→ Result Fusion → Rank → Results
 * Local Semantic Search ────────────────────────┘
 * ```
 *
 * [Disabled] is the only implementation in Phase 2. There is no embedding
 * model, no vector store, and no network call of any kind — the deterministic
 * engine is the product, and semantic search is an addition to it, never a
 * replacement (§35).
 */
interface SemanticSearchProvider {

    /** True when this provider can contribute; false means "skip the step". */
    val isEnabled: Boolean

    /**
     * Reorders or filters [candidates] (screenshot ids) for a free-text query.
     *
     * Implementations must be local and must not log the query.
     */
    suspend fun search(query: String, candidates: List<Long>): List<Long>

    /** No semantic search. The default, and the only implementation in Phase 2. */
    object Disabled : SemanticSearchProvider {
        override val isEnabled: Boolean = false
        override suspend fun search(query: String, candidates: List<Long>): List<Long> = candidates
    }
}
