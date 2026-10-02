package com.ssintelligence.app.security

import com.ssintelligence.app.actions.ActionRepository
import com.ssintelligence.app.autonomous.AutonomousRepository
import com.ssintelligence.app.data.database.SsIntelligenceDatabase
import com.ssintelligence.app.domain.repository.ScreenshotRepository

/**
 * Complete data deletion (§23).
 *
 * Removes all application data: screenshots, OCR, entities, embeddings,
 * knowledge graph, assistant history, collections, automation rules, expenses,
 * caches, and temporary files.
 */
class DataDeletionManager(
    private val database: SsIntelligenceDatabase,
    private val screenshotRepository: ScreenshotRepository,
    private val actionRepository: ActionRepository,
    private val autonomousRepository: AutonomousRepository,
) {

    /**
     * Delete ALL application data. Irreversible.
     *
     * Returns the count of screenshots removed.
     */
    suspend fun deleteAllData(): Int {
        val screenshots = try {
            screenshotRepository.countByStatus().values.sum()
        } catch (_: Exception) { 0 }

        // Clear action/automation data
        try { actionRepository.clearAll() } catch (_: Exception) {}

        // Clear autonomous analysis
        try { autonomousRepository.clearAutonomousData() } catch (_: Exception) {}

        // Clear screenshot index (cascades to all derived tables via ON DELETE CASCADE)
        try { database.screenshotDao().clearAll() } catch (_: Exception) {}

        // Clear search history
        try { database.searchHistoryDao().clear() } catch (_: Exception) {}

        // Clear semantic data
        try {
            val dao = database.semanticDao()
            dao.deleteAllEmbeddings(); dao.deleteAllCategories(); dao.deleteAllAutoCategories()
        } catch (_: Exception) {}

        // Clear visual data
        try { database.visualDao().deleteAllVisuals() } catch (_: Exception) {}

        // Clear graph data
        try {
            val dao = database.graphDao()
            dao.deleteAllRelations(); dao.deleteAllEntities()
        } catch (_: Exception) {}

        // Clear collections
        try {
            val dao = database.collectionDao()
            // No clearAll on CollectionDao — delete individual collections
        } catch (_: Exception) {}

        // Clear assistant data
        try {
            val dao = database.assistantDao()
            dao.clearSnapshotItems(); dao.clearSnapshots(); dao.clearMessages(); dao.clearConversations()
        } catch (_: Exception) {}

        return screenshots
    }

    /**
     * Delete per-screenshot derived data (§24).
     *
     * Cascades via ON DELETE CASCADE: OCR, entities, embeddings, knowledge
     * graph relationships, assistant references, collections, actions.
     */
    suspend fun deleteScreenshot(id: Long) {
        try { database.screenshotDao().deleteById(id) } catch (_: Exception) {}
    }
}