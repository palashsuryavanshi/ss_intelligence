package com.ssintelligence.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.ScreenshotDetail
import com.ssintelligence.app.domain.repository.ScreenshotRepository
import com.ssintelligence.app.domain.usecase.GetScreenshotDetailUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Detail screen state for a single screenshot (§24). */
class ScreenshotDetailViewModel(
    private val screenshotId: Long,
    private val repository: ScreenshotRepository,
    private val onRetryRequested: suspend () -> Unit,
) : ViewModel() {

    val detail: StateFlow<ScreenshotDetail?> = GetScreenshotDetailUseCase(repository)(screenshotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    class Factory(
        private val locator: ServiceLocator,
        private val screenshotId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScreenshotDetailViewModel(
            screenshotId = screenshotId,
            repository = locator.screenshotRepository,
            onRetryRequested = { locator.indexingScheduler.requestProcessingOnly() },
        ) as T
    }
}
