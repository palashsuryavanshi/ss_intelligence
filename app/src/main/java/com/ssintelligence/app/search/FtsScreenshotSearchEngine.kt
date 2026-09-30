package com.ssintelligence.app.search

import com.ssintelligence.app.data.database.ScreenshotDao
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.SearchFilter
import com.ssintelligence.app.domain.search.ScreenshotSearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 1 FTS-backed search engine (§25–§26, §28).
 *
 * Replace this class in Phase 2/4 to add structured query understanding or
 * semantic ranking — callers depend on [ScreenshotSearchEngine] only.
 */
class FtsScreenshotSearchEngine(
    private val dao: ScreenshotDao,
) : ScreenshotSearchEngine {

    override fun search(
        query: String,
        filter: SearchFilter,
        limit: Int,
    ): Flow<List<Screenshot>> {
        val match = FtsQueryBuilder.build(query)
        // Without usable tokens there is nothing to match: fall back to a
        // filter-only browse so the UI never shows a misleading empty state.
            ?: return dao.observeFiltered(filter.name, limit)
                .map { rows -> rows.map { it.toDomain() } }

        val trimmed = query.trim()
        val lower = trimmed.lowercase()
        return dao.search(
            ftsQuery = match,
            lowerQuery = lower,
            prefixQuery = FtsQueryBuilder.escapeLike(lower) + "%",
            containsQuery = "%" + FtsQueryBuilder.escapeLike(lower) + "%",
            filterType = filter.name,
            limit = limit,
        ).map { rows -> rows.map { it.screenshot.toDomain() } }
    }
}

private fun ScreenshotEntity.toDomain() = Screenshot(
    id = id,
    mediaStoreId = mediaStoreId,
    uri = uri,
    filename = filename,
    relativePath = relativePath,
    dateAdded = dateAdded,
    dateModified = dateModified,
    fileSize = fileSize,
    width = width,
    height = height,
    mimeType = mimeType,
    ocrText = ocrText,
    contentHash = contentHash,
    duplicateOfId = duplicateOfId,
    status = runCatching { ProcessingStatus.valueOf(status) }.getOrDefault(ProcessingStatus.PENDING),
    error = processingError,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
