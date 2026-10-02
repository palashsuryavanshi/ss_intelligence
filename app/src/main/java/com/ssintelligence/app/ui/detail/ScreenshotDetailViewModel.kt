package com.ssintelligence.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.actions.ActionCandidateGenerator
import com.ssintelligence.app.actions.ActionExecutor
import com.ssintelligence.app.actions.ActionPayload
import com.ssintelligence.app.actions.ActionRepository
import com.ssintelligence.app.actions.ActionResult
import com.ssintelligence.app.actions.ActionType
import com.ssintelligence.app.actions.ActionValidator
import com.ssintelligence.app.actions.ConfirmationLevel
import com.ssintelligence.app.actions.ContextAction
import com.ssintelligence.app.actions.PermissionType
import com.ssintelligence.app.assistant.SensitivityLevel
import com.ssintelligence.app.domain.model.ScreenshotDetail
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.repository.SettingsRepository
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
import kotlinx.coroutines.flow.map
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
    private val settings: SettingsRepository,
    private val actionRepository: ActionRepository,
    private val executor: ActionExecutor,
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

    /**
     * Phase 7 contextual actions, generated from the screenshot's actual
     * detected content: URLs, phone numbers, emails, addresses, dates and
     * prices. An action that cannot apply is never built (§4).
     *
     * Derived from the detail Flow rather than built once: the detail arrives
     * asynchronously, and building eagerly would race it and show nothing.
     */
    val contextActions: StateFlow<List<ContextAction>> = detail
        .map { buildContextActions(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The last executed action's result, for confirmation feedback (§43). */
    private val _actionResult = MutableStateFlow<ActionResult?>(null)
    val actionResult: StateFlow<ActionResult?> = _actionResult.asStateFlow()

    /** Whether contextual action suggestions are enabled in Settings. */
    private val _contextActionsEnabled = MutableStateFlow(true)
    val contextActionsEnabled: StateFlow<Boolean> = _contextActionsEnabled.asStateFlow()

    private val generator = ActionCandidateGenerator()

    init {
        refreshSemantic()
        refreshVisual()
        refreshToggles()
    }

    private fun refreshToggles() {
        viewModelScope.launch {
            _contextActionsEnabled.value = runCatching { settings.areContextActionsEnabled() }
                .getOrDefault(true)
        }
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
     * Builds Phase 7 contextual actions from the detail's extracted tables.
     *
     * Emails and addresses are mined from the OCR text with the same
     * conservative patterns the rest of the app uses; everything else comes
     * from the normalized extraction tables.
     */
    private fun buildContextActions(detailValue: ScreenshotDetail?): List<ContextAction> {
        if (detailValue == null) return emptyList()
        val ocr = detailValue.screenshot.ocrText
        val emails = EMAIL_PATTERN.findAll(ocr)
            .map { it.value.replace(Regex("\\s+"), "") }
            .distinct().take(2).toList()
        val addresses = ADDRESS_PATTERN.findAll(ocr).map { it.value.trim() }.distinct().take(2).toList()
        return generator.generate(
            screenshotId = screenshotId,
            ocrText = ocr,
            urls = detailValue.urls.map { it },
            phones = detailValue.phones.map { it.normalized },
            emails = emails,
            addresses = addresses,
            dates = detailValue.dates.map { it.epochDay * 86_400_000L to it.rawText },
            prices = detailValue.prices.map { it.amount to it.currency },
            hasOcrText = ocr.isNotBlank(),
        )
    }

    /**
     * Executes one contextual action (§27, §43).
     *
     * [permissionGranted] is supplied by the screen, which owns the runtime
     * permission request. The result — success or honest failure — is reported
     * back through [actionResult] and recorded in the action history.
     */
    fun executeAction(action: ContextAction, permissionGranted: Boolean) {
        viewModelScope.launch {
            val validation = ActionValidator.validate(
                ActionValidator.Request(action, permissionGranted, action.sensitivity),
            )
            if (!validation.valid) {
                _actionResult.value = ActionResult(action.id, false, validation.reason ?: "Action unavailable")
                return@launch
            }
            val result = runCatching { executor.execute(action) }
                .getOrElse { ActionResult(action.id, false, "Action failed") }
            runCatching {
                actionRepository.recordAction(
                    actionType = action.type.name,
                    screenshotId = screenshotId,
                    title = action.title,
                    success = result.success,
                )
            }
            _actionResult.value = result
        }
    }

    fun clearActionResult() {
        _actionResult.value = null
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

        /**
         * Email addresses, tolerating the spaces OCR inserts around `@` and
         * dots (`user @domain .com`). The match is normalized by stripping
         * whitespace; the `@` and TLD requirements keep false positives out.
         */
        private val EMAIL_PATTERN =
            Regex("[a-zA-Z0-9._%+-]+\\s*@\\s*[a-zA-Z0-9.-]+\\s*\\.\\s*[a-zA-Z]{2,}")

        /**
         * Street addresses are matched conservatively: a house number, a street
         * word, and a city-like tail. A bare number is never an address.
         */
        private val ADDRESS_PATTERN =
            Regex("\\d{1,5}\\s+[A-Za-z][A-Za-z .-]{3,40}(?:Street|St|Road|Rd|Avenue|Ave|Lane|Nagar|Colony|Layout|Marg|Chowk)")
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
            settings = locator.settingsRepository,
            actionRepository = locator.actionRepository,
            executor = ActionExecutor(locator.toApplicationContext()),
            onRetryRequested = { locator.indexingScheduler.requestProcessingOnly() },
        ) as T
    }
}
