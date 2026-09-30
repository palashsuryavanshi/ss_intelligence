package com.ssintelligence.app.data.database

import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot

/**
 * Single mapping from the Room row to the domain model.
 *
 * Shared so the repository and the search engine cannot drift: two copies of
 * this function is how a field quietly gets set in one path and forgotten in
 * the other.
 */
internal fun ScreenshotEntity.toDomain(): Screenshot = Screenshot(
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
    status = runCatching { ProcessingStatus.valueOf(status) }
        .getOrDefault(ProcessingStatus.PENDING),
    error = processingError,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
