package com.ssintelligence.app.domain.usecase

import com.ssintelligence.app.domain.model.IndexingProgress
import com.ssintelligence.app.domain.model.IndexingStats
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.model.ScreenshotDetail
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.domain.repository.SettingsRepository
import com.ssintelligence.app.indexing.IndexingScheduler
import com.ssintelligence.app.search.SearchQuery
import com.ssintelligence.app.search.SearchRequest
import com.ssintelligence.app.search.SearchResponse
import com.ssintelligence.app.search.SearchSuggestion
import com.ssintelligence.app.search.ScreenshotSearchEngine
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
 * Structured search with filters (§2, §25).
 *
 * Keystroke debouncing is a UI concern and lives in the ViewModel, so this use
 * case stays a thin, directly testable mapping from a [SearchRequest] to a
 * [SearchResponse].
 */
class SearchScreenshotsUseCase(
    private val engine: ScreenshotSearchEngine,
) {
    operator fun invoke(request: SearchRequest): Flow<SearchResponse> = engine.observe(request)
}

/**
 * Understands a query without running it (§25).
 *
 * Used to show "Searching for Pixel 9a · ₹39,999" above the results, so the
 * user can see what the engine understood before the results arrive.
 */
class ParseSearchQueryUseCase(
    private val engine: ScreenshotSearchEngine,
) {
    suspend operator fun invoke(query: String): SearchQuery = engine.parse(query)
}

/** Local autocomplete from recent searches, indexed hosts and OCR phrases (§38). */
class ObserveSearchSuggestionsUseCase(
    private val engine: ScreenshotSearchEngine,
) {
    operator fun invoke(prefix: String): Flow<List<SearchSuggestion>> = engine.suggestions(prefix)
}

/** Recent searches, empty unless the user opted in (§24, §37). */
class ObserveRecentSearchesUseCase(
    private val history: SearchHistoryRepository,
) {
    operator fun invoke(limit: Int = SearchHistoryRepository.DEFAULT_LIMIT): Flow<List<String>> =
        history.observeRecent(limit)
}

/**
 * Records a submitted query in the local history.
 *
 * Skips anything that looks like it carries a one-time code, and does nothing
 * at all unless history is enabled — see [SearchHistoryRepository].
 */
class RecordSearchUseCase(
    private val history: SearchHistoryRepository,
) {
    suspend operator fun invoke(query: String) = history.record(query)
}

/** "Clear search history" in Settings (§36). */
class ClearSearchHistoryUseCase(
    private val history: SearchHistoryRepository,
) {
    suspend operator fun invoke() = history.clear()
}

/**
 * Semantic detail for one screenshot (§47 Phase 3).
 *
 * Summary, categories, sensitive flags and related screenshots are derived
 * locally from the OCR text and the semantic index. Nothing here needs the
 * network, and nothing here sends anything anywhere.
 */
class GetSemanticDetailUseCase(
    private val semantic: com.ssintelligence.app.semantic.SemanticRepository,
    private val repository: ScreenshotRepository,
) {
    data class SemanticDetail(
        val summary: String,
        val categories: List<com.ssintelligence.app.semantic.CategoryAssignment>,
        val sensitive: Set<com.ssintelligence.app.semantic.SensitiveKind>,
        val relatedIds: List<Long>,
    )

    suspend operator fun invoke(screenshotId: Long): SemanticDetail? {
        val detail = repository.getDetail(screenshotId) ?: return null
        if (!semantic.isAvailable) {
            return SemanticDetail(
                summary = "",
                categories = emptyList(),
                sensitive = emptySet(),
                relatedIds = emptyList(),
            )
        }
        val screenshot = detail.screenshot
        val document = com.ssintelligence.app.semantic.ScreenshotDocument(
            screenshotId = screenshotId,
            ocrText = screenshot.ocrText,
            filename = screenshot.filename,
            hosts = detail.urls.map { url -> url.host },
            prices = detail.prices,
            dateTexts = detail.dates.map { date -> date.rawText },
            phoneCount = detail.phones.size,
            otpCount = detail.otps.size,
        )
        val phrases = screenshot.ocrText.lineSequence()
            .map { line -> line.trim() }
            .filter { it.length >= 3 }
            .take(3)
            .toList()
        val related = semantic.findSimilarTo(document)
        return SemanticDetail(
            summary = semantic.summarize(document, phrases),
            categories = semantic.categoriesFor(screenshotId),
            sensitive = com.ssintelligence.app.semantic.SensitiveContentDetector.detect(document),
            relatedIds = related.map { match -> match.screenshotId },
        )
    }
}

/** User correction of an automatic category (§21 Phase 3). */
class SetScreenshotCategoryUseCase(
    private val semantic: com.ssintelligence.app.semantic.SemanticRepository,
) {
    suspend operator fun invoke(screenshotId: Long, category: com.ssintelligence.app.semantic.ScreenshotCategory) =
        semantic.setUserCategory(screenshotId, category)
}

/** Removes the user's correction, restoring the automatic classification. */
class ClearScreenshotCategoryUseCase(
    private val semantic: com.ssintelligence.app.semantic.SemanticRepository,
) {
    suspend operator fun invoke(screenshotId: Long) = semantic.clearUserCategory(screenshotId)
}

/** Smart collections for Home, built from local categories and hosts (§24 Phase 3). */
class ObserveSmartGroupsUseCase(
    private val repository: ScreenshotRepository,
) {
    suspend operator fun invoke(): List<com.ssintelligence.app.semantic.SmartGroup> =
        repository.smartGroups()
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
