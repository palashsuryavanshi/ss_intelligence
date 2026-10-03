package com.ssintelligence.app.ui.similar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.usecase.FindVisuallySimilarUseCase
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow
import kotlinx.coroutines.launch
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Visually similar screenshots (§6).
 *
 * Ranked by perceptual-hash Hamming distance: same layout, same palette, same
 * shapes. OCR text may differ completely — that is the point. Distances map to
 * words ("Near duplicate", "Very similar", "Similar"), never to numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarScreen(
    locator: ServiceLocator,
    screenshotId: Long,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: SimilarViewModel = viewModel(factory = SimilarViewModel.Factory(locator, screenshotId)),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visually similar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = results) {
            null -> EmptyState(
                title = "Looking…",
                message = "Comparing pixels on this device.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            else -> if (current.isEmpty()) {
                EmptyState(
                    title = "Nothing visually similar",
                    message = "No other screenshot shares this one's layout and palette.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(current, key = { it.screenshot.id }) { similar ->
                        SimilarRow(
                            screenshot = similar.screenshot,
                            distanceBits = similar.distanceBits,
                            onClick = { onOpenScreenshot(similar.screenshot.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarRow(
    screenshot: Screenshot,
    distanceBits: Int,
    onClick: () -> Unit,
) {
    val label = when {
        distanceBits == 0 -> "Identical pixels"
        distanceBits <= com.ssintelligence.app.vision.ImageEmbedding.NEAR_DUPLICATE_BITS ->
            "Near duplicate"

        distanceBits <= 16 -> "Very similar"
        else -> "Similar"
    }
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = SsColors.SurfaceVariant,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.ssintelligence.app.ui.common.ScreenshotThumbnail(
                screenshot = screenshot,
                modifier = com.ssintelligence.app.ui.common.thumbnailModifier(),
                contentDescription = null,
            )
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = screenshot.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = SsColors.TextSecondary,
                )
                Text(
                    text = com.ssintelligence.app.ui.common.DateFormats.formatDate(screenshot.dateAdded),
                    style = MaterialTheme.typography.labelMedium,
                    color = SsColors.TextSecondary,
                )
            }
        }
    }
}

class SimilarViewModel(
    private val screenshotId: Long,
    private val findSimilar: FindVisuallySimilarUseCase,
) : ViewModel() {

    private val _results = kotlinx.coroutines.flow.MutableStateFlow<
        List<com.ssintelligence.app.domain.repository.VisualSimilar>?
        >(null)
    val results: kotlinx.coroutines.flow.StateFlow<
        List<com.ssintelligence.app.domain.repository.VisualSimilar>?
        > = _results

    init {
        viewModelScope.launch {
            _results.value = runCatching { findSimilar(screenshotId) }.getOrDefault(emptyList())
        }
    }

    class Factory(
        private val locator: ServiceLocator,
        private val screenshotId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SimilarViewModel(
            screenshotId = screenshotId,
            findSimilar = FindVisuallySimilarUseCase(locator.screenshotRepository),
        ) as T
    }
}
