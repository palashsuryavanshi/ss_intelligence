package com.ssintelligence.app.domain.search

import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.SearchFilter
import kotlinx.coroutines.flow.Flow

/**
 * Phase 1 search: plain text search over OCR content (§25–§28).
 *
 * The interface is deliberately narrow so a future semantic implementation
 * (Phase 4: local embeddings + vector search) can replace this FTS-based
 * engine without touching callers:
 *
 * ```
 * User Query → Query Understanding → Structured Search → SQLite/FTS → Ranked Results
 * ```
 */
interface ScreenshotSearchEngine {
    fun search(query: String, filter: SearchFilter, limit: Int = 200): Flow<List<Screenshot>>
}
