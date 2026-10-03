package com.ssintelligence.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.usecase.ObserveIndexingStatsUseCase
import com.ssintelligence.app.domain.usecase.ObserveSmartGroupsUseCase
import com.ssintelligence.app.semantic.SmartGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home screen state (§22). Everything is derived from database counters so the
 * numbers remain correct after process death.
 */
class HomeViewModel(
    private val observeStats: ObserveIndexingStatsUseCase,
    private val observeGroups: ObserveSmartGroupsUseCase,
    private val locator: ServiceLocator,
) : ViewModel() {

    val uiState: StateFlow<ObserveIndexingStatsUseCase.UiState> = observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObserveIndexingStatsUseCase.UiState(
            stats = com.ssintelligence.app.domain.model.IndexingStats(0, 0, 0, 0, 0, 0, 0),
            isIndexing = false,
        ))

    /**
     * Smart collections, loaded once per subscription rather than observed:
     * groups change only when the index or the categories change, and a
     * recompute per keystroke would be waste. Refreshed on demand.
     */
    private val _groups = MutableStateFlow(emptyList<SmartGroup>())
    val groups: StateFlow<List<SmartGroup>> = _groups.asStateFlow()

    /**
     * One quiet suggestion (§35): the most-referenced product entity, shown
     * only when it covers enough screenshots to be worth opening. Inside the
     * app, never a notification.
     */
    private val _suggestion = MutableStateFlow<TopEntitySuggestion?>(null)
    val suggestion: StateFlow<TopEntitySuggestion?> = _suggestion.asStateFlow()

    data class TopEntitySuggestion(
        val entityId: Long,
        val label: String,
        val count: Int,
    )

    /**
     * Important screenshots from the autonomous analysis (§11).
     *
     * Derived from the engine's importance scoring — confirmations, tickets,
     * orders, receipts — never used to delete anything.
     */
    private val _important = MutableStateFlow(emptyList<com.ssintelligence.app.domain.model.Screenshot>())
    val important: StateFlow<List<com.ssintelligence.app.domain.model.Screenshot>> = _important.asStateFlow()

    /**
     * Reviewable insights for the insights center (§51).
     */
    private val _insights = MutableStateFlow(emptyList<Insight>())
    val insights: StateFlow<List<Insight>> = _insights.asStateFlow()

    data class Insight(
        val id: String,
        val title: String,
        val detail: String,
        val kind: com.ssintelligence.app.autonomous.InsightKind,
    )

    init {
        refreshGroups()
        refreshSuggestion()
        refreshAutonomous()
    }

    fun refreshAutonomous() {
        viewModelScope.launch {
            val repository = locator.autonomousRepository
            val events = runCatching { repository.events() }.getOrDefault(emptyList())
            val sessions = runCatching { repository.sessions() }.getOrDefault(emptyList())
            // Important: screenshots whose event is confirmed or strongly matched.
            // Batched into one query instead of one getById per id (N+1).
            val importantIds = events
                .filter { it.confidence == com.ssintelligence.app.autonomous.EventConfidence.CONFIRMED ||
                    it.confidence == com.ssintelligence.app.autonomous.EventConfidence.STRONG }
                .flatMap { it.screenshotIds }
                .distinct()
            _important.value = runCatching {
                locator.screenshotRepository.getByIds(importantIds)
            }.getOrDefault(emptyList())
            // Insights: one line per detected structure, all reviewable.
            val insights = buildList {
                val topicCount = runCatching { repository.topics() }.getOrDefault(emptyList()).size
                if (topicCount > 0) {
                    add(Insight("topics", "$topicCount topics detected", "Grouped from your screenshots", com.ssintelligence.app.autonomous.InsightKind.TOPIC))
                }
                if (sessions.isNotEmpty()) {
                    add(Insight("sessions", "${sessions.size} research sessions", "Screenshots grouped by activity", com.ssintelligence.app.autonomous.InsightKind.SESSION))
                }
                if (events.isNotEmpty()) {
                    add(Insight("events", "${events.size} possible events", "Booking, purchase and payment sequences", com.ssintelligence.app.autonomous.InsightKind.EVENT))
                }
                val nearDupes = runCatching { locator.screenshotRepository.nearDuplicateGroups(20) }.getOrDefault(emptyList())
                if (nearDupes.isNotEmpty()) {
                    add(Insight("dupes", "${nearDupes.size} near-duplicate groups", "Similar screenshots to review", com.ssintelligence.app.autonomous.InsightKind.DUPLICATE))
                }
            }
            _insights.value = insights
        }
    }

    fun refreshGroups() {
        viewModelScope.launch {
            _groups.value = runCatching { observeGroups() }.getOrDefault(emptyList())
        }
    }

    fun refreshSuggestion() {
        viewModelScope.launch {
            val top = runCatching {
                locator.screenshotRepository.topEntities(
                    listOf(com.ssintelligence.app.graph.GraphEntityType.PRODUCT),
                    minCount = SUGGESTION_MIN_COUNT,
                    limit = 1,
                )
            }.getOrDefault(emptyList()).firstOrNull()
            _suggestion.value = top?.let {
                TopEntitySuggestion(it.entity.id, it.entity.displayName, it.screenshotCount)
            }
        }
    }

    fun onScanNow() {
        locator.indexingScheduler.requestIndexing()
    }

    fun onStopIndexing() {
        locator.indexingScheduler.cancel()
    }

    private companion object {
        /** A suggestion needs enough screenshots to be worth opening. */
        const val SUGGESTION_MIN_COUNT = 5
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
            observeStats = ObserveIndexingStatsUseCase(
                repository = locator.screenshotRepository,
                scheduler = locator.indexingScheduler,
            ),
            observeGroups = ObserveSmartGroupsUseCase(locator.screenshotRepository),
            locator = locator,
        ) as T
    }
}
