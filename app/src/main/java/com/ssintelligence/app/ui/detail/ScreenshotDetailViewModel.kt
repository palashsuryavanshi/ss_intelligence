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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    init {
        refreshSemantic()
    }

    fun refreshSemantic() {
        viewModelScope.launch {
            _semanticDetail.value = GetSemanticDetailUseCase(semantic, repository)(screenshotId)
        }
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
