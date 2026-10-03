package com.ssintelligence.app.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.compare.ScreenshotComparison
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.domain.usecase.CompareScreenshotsUseCase
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow
import com.ssintelligence.app.ui.common.ScreenshotThumbnail
import com.ssintelligence.app.ui.common.SectionHeader
import com.ssintelligence.app.ui.common.thumbnailModifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Side-by-side comparison (§26, §55).
 *
 * Thumbnails on top, detected differences below: moved prices and websites as
 * change statements, plus added and removed terms. OCR/entity comparison only —
 * pixel diffing would report status-bar icons and ad rotation as change, which
 * is noise dressed as information.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    locator: ServiceLocator,
    firstId: Long,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    onPickSecond: () -> Unit,
    viewModel: CompareViewModel = viewModel(factory = CompareViewModel.Factory(locator, firstId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val current = state
        if (current?.comparison == null) {
            EmptyState(
                title = "Pick a second screenshot",
                message = "Comparison needs two screenshots. The first is set; choose " +
                    "what to compare it against.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                action = {
                    androidx.compose.material3.TextButton(onClick = onPickSecond) {
                        Text("Choose screenshot")
                    }
                },
            )
            return@Scaffold
        }
        val comparison = current.comparison
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("images") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.first?.let {
                        ScreenshotThumbnail(
                            screenshot = it,
                            modifier = Modifier.weight(1f),
                            contentDescription = "First screenshot: ${it.filename}",
                        )
                    }
                    current.second?.let {
                        ScreenshotThumbnail(
                            screenshot = it,
                            modifier = Modifier.weight(1f),
                            contentDescription = "Second screenshot: ${it.filename}",
                        )
                    }
                }
            }
            if (comparison.changes.isNotEmpty()) {
                item("changes-header") { SectionHeader("Differences") }
                items(comparison.changes, key = { "change-$it" }) { change ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = SsColors.NavyPrimary,
                            contentColor = SsColors.TextOnNavy,
                        ),
                    ) {
                        Text(
                            text = change,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            } else {
                item("no-changes") {
                    Text(
                        text = "No detected differences in prices or websites.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SsColors.TextSecondary,
                    )
                }
            }
            if (comparison.addedTerms.isNotEmpty() || comparison.removedTerms.isNotEmpty()) {
                item("terms-header") { SectionHeader("Text differences") }
                if (comparison.addedTerms.isNotEmpty()) {
                    item("added") {
                        Text(
                            text = "+ ${comparison.addedTerms.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (comparison.removedTerms.isNotEmpty()) {
                    item("removed") {
                        Text(
                            text = "− ${comparison.removedTerms.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SsColors.TextSecondary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

class CompareViewModel(
    private val locator: ServiceLocator,
    private val firstId: Long,
) : ViewModel() {

    data class State(
        val first: Screenshot?,
        val second: Screenshot?,
        val comparison: ScreenshotComparison?,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = State(
                first = locator.screenshotRepository.getById(firstId),
                second = null,
                comparison = null,
            )
        }
    }

    fun compareWith(secondId: Long) {
        viewModelScope.launch {
            val comparison = CompareScreenshotsUseCase(locator.screenshotRepository)(firstId, secondId)
            _state.value = State(
                first = locator.screenshotRepository.getById(firstId),
                second = locator.screenshotRepository.getById(secondId),
                comparison = comparison,
            )
        }
    }

    class Factory(
        private val locator: ServiceLocator,
        private val firstId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CompareViewModel(locator, firstId) as T
    }
}

/**
 * Host for the compare flow: shows the comparison when both ids are known,
 * otherwise a screenshot picker for the second slot (§26).
 *
 * The picker is the recent-screenshots list — the thing being compared against
 * is usually recent. Picking navigates to the full two-id route, so back
 * returns to the picker rather than losing the first screenshot.
 */
@Composable
fun CompareScreenHost(
    locator: ServiceLocator,
    firstId: Long,
    secondId: Long?,
    navController: androidx.navigation.NavHostController,
) {
    if (secondId == null) {
        ComparePicker(
            locator = locator,
            firstId = firstId,
            onBack = { navController.popBackStack() },
            onPick = { picked ->
                navController.navigate("compare/$firstId?secondId=$picked") {
                    popUpTo("compare/$firstId") { inclusive = true }
                }
            },
        )
    } else {
        val viewModel: CompareViewModel = viewModel(
            factory = CompareViewModel.Factory(locator, firstId),
        )
        androidx.compose.runtime.LaunchedEffect(secondId) {
            viewModel.compareWith(secondId)
        }
        CompareScreen(
            locator = locator,
            firstId = firstId,
            onBack = { navController.popBackStack() },
            onOpenScreenshot = { },
            onPickSecond = { },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComparePicker(
    locator: ServiceLocator,
    firstId: Long,
    onBack: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val recent by locator.screenshotRepository
        .observeRecent(60)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare with…") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(recent.filter { it.id != firstId }, key = { it.id }) { screenshot ->
                ScreenshotRow(
                    screenshot = screenshot,
                    onClick = { onPick(screenshot.id) },
                )
            }
        }
    }
}
