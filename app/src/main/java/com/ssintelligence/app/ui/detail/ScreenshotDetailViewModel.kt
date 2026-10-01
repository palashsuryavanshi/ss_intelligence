package com.ssintelligence.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.ScreenshotDetail
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.usecase.ClearScreenshotCategoryUseCase
import com.ssintelligence.app.domain.usecase.GetScreenshotDetailUseCase
import com.ssintelligence.app.domain.usecase.GetSemanticDetailUseCase
import com.ssintelligence.app.domain.usecase.SetScreenshotCategoryUseCase
import com.ssintelligence.app.semantic.ScreenshotCategory
import com.ssintelligence.app.semantic.SemanticRepository
import com.ssintelligence.app.domain.repository.VisualInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One contextual search action (§34).
 *
 * Every action carries its destination data, resolved against the index before
 * it is shown. An action that would lead nowhere is never constructed.
 */
sealed interface DetailAction {
    /** Rank the library by visual similarity to this screenshot. */
    data object FindVisuallySimilar : DetailAction

    /** Domain search over the indexed host. */
    data class MoreFromWebsite(val domain: String) : DetailAction

    /** Entity page for a filed entity. */
    data class MoreAboutEntity(val entityId: Long, val label: String) : DetailAction

    /** Open the comparison screen with this screenshot preset. */
    data object Compare : DetailAction
}

/** Detail screen state for a single screenshot (§24, §47 Phase 3). */
class ScreenshotDetailViewModel(
    private val screenshotId: Long,
    private val repository: ScreenshotRepository,
    private val semantic: SemanticRepository,
    private val onRetryRequested: suspend () -> Unit,
) : ViewModel() {

    val detail: StateFlow<ScreenshotDetail?> = GetScreenshotDetailUseCase(repository)(screenshotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _semanticDetail = MutableStateFlow<GetSemanticDetailUseCase.SemanticDetail?>(null)
    val semanticDetail: StateFlow<GetSemanticDetailUseCase.SemanticDetail?> =
        _semanticDetail.asStateFlow()

    /** Visual row for the palette/type section. Null when never analyzed. */
    private val _visualDetail = MutableStateFlow<VisualInfo?>(null)
    val visualDetail: StateFlow<VisualInfo?> = _visualDetail.asStateFlow()

    /**
     * Contextual actions generated from actual indexed data (§34).
     *
     * Each action only exists when it has somewhere to go: no host means no
     * "More from this website", no visual row means no visual similarity.
     */
    private val _actions = MutableStateFlow<List<DetailAction>>(emptyList())
    val actions: StateFlow<List<DetailAction>> = _actions.asStateFlow()

    init {
        refreshSemantic()
        refreshVisual()
    }

    fun refreshSemantic() {
        viewModelScope.launch {
            _semanticDetail.value = GetSemanticDetailUseCase(semantic, repository)(screenshotId)
        }
    }

    fun refreshVisual() {
        viewModelScope.launch {
            val info = repository.visualFor(screenshotId)
            _visualDetail.value = info
            _actions.value = buildActions(info)
        }
    }

    private suspend fun buildActions(visual: VisualInfo?): List<DetailAction> {
        val actions = mutableListOf<DetailAction>()
        if (visual != null) {
            actions += DetailAction.FindVisuallySimilar
        }
        val detailValue = detail.value ?: return actions + DetailAction.Compare
        detailValue.urls.firstOrNull()?.let { url ->
            actions += DetailAction.MoreFromWebsite(url.host)
        }
        // Entity actions resolve through the graph: only filed entities get
        // actions, so "More about X" never leads nowhere.
        val phrases = detailValue.screenshot.ocrText.lineSequence()
            .map { it.trim() }
            .filter { it.length in 4..60 }
            .take(4)
            .toList()
        for (phrase in phrases) {
            val normalized = com.ssintelligence.app.graph.EntityNormalization.product(phrase)
            if (normalized.length < 4) continue
            val entity = repository.resolveEntity(
                com.ssintelligence.app.graph.GraphEntityType.PRODUCT,
                normalized,
            )
            if (entity != null) {
                actions += DetailAction.MoreAboutEntity(entity.id, entity.displayName)
                if (actions.size >= MAX_ACTIONS) break
            }
        }
        if (actions.none { it is DetailAction.MoreAboutEntity }) {
            detailValue.urls.firstOrNull()?.let { url ->
                val normalized = com.ssintelligence.app.graph.EntityNormalization.website(url.host)
                repository.resolveEntity(
                    com.ssintelligence.app.graph.GraphEntityType.WEBSITE,
                    normalized,
                )?.let { entity ->
                    actions += DetailAction.MoreAboutEntity(entity.id, entity.displayName)
                }
            }
        }
        return (actions + DetailAction.Compare).distinct().take(MAX_ACTIONS + 1)
    }

    /**
     * Retries a single screenshot without rescanning MediaStore (§30).
     * The row returns to PENDING and the existing work queue picks it up.
     */
    fun onRetry() {
        viewModelScope.launch {
            repository.requeueScreenshot(screenshotId)
            onRetryRequested()
        }
    }

    /** User correction of the automatic category (§21). Never overwritten. */
    fun onCategorySelected(category: ScreenshotCategory) {
        viewModelScope.launch {
            SetScreenshotCategoryUseCase(semantic)(screenshotId, category)
            refreshSemantic()
        }
    }

    fun onCategoryCleared() {
        viewModelScope.launch {
            ClearScreenshotCategoryUseCase(semantic)(screenshotId)
            refreshSemantic()
        }
    }
    private companion object {
        const val MAX_ACTIONS = 4
    }

    class Factory(
        private val locator: ServiceLocator,
        private val screenshotId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScreenshotDetailViewModel(
            screenshotId = screenshotId,
            repository = locator.screenshotRepository,
            semantic = locator.semanticRepository,
            onRetryRequested = { locator.indexingScheduler.requestProcessingOnly() },
        ) as T
    }
}
