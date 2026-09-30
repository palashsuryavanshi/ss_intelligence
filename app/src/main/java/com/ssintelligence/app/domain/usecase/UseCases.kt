package com.ssintelligence.app.domain.usecase

import com.ssintelligence.app.domain.model.IndexingProgress
import com.ssintelligence.app.domain.model.IndexingStats
import com.ssintelligence.app.domain.model.SearchFilter
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.ScreenshotDetail
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.repository.SettingsRepository
import com.ssintelligence.app.domain.search.ScreenshotSearchEngine
import com.ssintelligence.app.indexing.IndexingScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Aggregate counts and progress for the home screen (§22). */
class ObserveIndexingStatsUseCase(
    private val repository: ScreenshotRepository,
    private val scheduler: IndexingScheduler,
) {
    data class UiState(
        val stats: IndexingStats,
        val isIndexing: Boolean,
    ) {
        val isEmpty: Boolean get() = stats.total == 0
        val progress: Float
            get() = if (stats.total == 0) 0f else stats.completed.toFloat() / stats.total
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<UiState> =
        scheduler.observeWorkState()
            .flatMapLatest { workState ->
                val indexing = workState == androidx.work.WorkInfo.State.RUNNING ||
                    workState == androidx.work.WorkInfo.State.ENQUEUED
                repository.observeStats().map { UiState(stats = it, isIndexing = indexing) }
            }
}

/** First-run gate (§33). */
class ObserveOnboardingUseCase(
    private val settings: SettingsRepository,
) {
    operator fun invoke(): Flow<Boolean> = settings.observeOnboardingDone()
    suspend fun complete() = settings.setOnboardingDone(true)
}

/** The screenshot browser, with filter but no text query (§23, §26). */
class BrowseScreenshotsUseCase(
    private val repository: ScreenshotRepository,
) {
    operator fun invoke(limit: Int = 200): Flow<List<Screenshot>> =
        repository.observeRecent(limit)

    fun recent(limit: Int): Flow<List<Screenshot>> = repository.observeRecent(limit)
}

/**
 * Full-text search with filters (§25, §26).
 *
 * Keystroke debouncing is a UI concern and lives in the ViewModel, so this use
 * case stays a thin, directly testable mapping from (query, filter) to results.
 */
class SearchScreenshotsUseCase(
    private val engine: ScreenshotSearchEngine,
) {
    operator fun invoke(query: String, filter: SearchFilter): Flow<List<Screenshot>> =
        engine.search(query, filter)
}

/** Detail data for one screenshot (§24). */
class GetScreenshotDetailUseCase(
    private val repository: ScreenshotRepository,
) {
    operator fun invoke(id: Long): Flow<ScreenshotDetail?> = repository.observeDetail(id)
}

/** Duplicate groups (§22, §18). */
class ObserveDuplicateGroupsUseCase(
    private val repository: ScreenshotRepository,
) {
    operator fun invoke(limit: Int = 60): Flow<List<com.ssintelligence.app.domain.model.DuplicateGroup>> =
        repository.observeDuplicateGroups(limit)
}

/** Snapshot of how much work remains (§7). */
class ObserveIndexingProgressUseCase(
    private val repository: ScreenshotRepository,
) {
    operator fun invoke(): Flow<IndexingProgress> = repository.observeStats().map {
        IndexingProgress(total = it.total, processed = it.completed, failed = it.failed)
    }
}

private const val DEBOUNCE_MS = 250L
