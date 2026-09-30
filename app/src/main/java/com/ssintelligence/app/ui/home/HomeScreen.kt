package com.ssintelligence.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.usecase.ObserveIndexingStatsUseCase
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.IndexingProgressCard
import com.ssintelligence.app.ui.common.ScreenshotRow
import com.ssintelligence.app.ui.common.SectionHeader

/**
 * Home screen (§22): headline counters, live indexing status, and shortcuts
 * into search, browse, duplicates and settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    locator: ServiceLocator,
    onNavigateToSearch: () -> Unit,
    onNavigateToBrowse: () -> Unit,
    onNavigateToDuplicates: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(locator)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recent by locator.screenshotRepository
        .observeRecent(RECENT_LIMIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SS Intelligence") },
                actions = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Search screenshots")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Open settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("privacy-note") {
                Text(
                    text = "Everything is processed on this device. No account, no uploads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item("counters") {
                StatsCard(state = state)
            }

            if (state.isIndexing) {
                item("progress") {
                    IndexingProgressCard(
                        processed = state.stats.completed,
                        total = state.stats.total,
                        onStop = viewModel::onStopIndexing,
                    )
                }
            }

            item("actions") {
                ActionRow(
                    onScanNow = viewModel::onScanNow,
                    onSearch = onNavigateToSearch,
                    onBrowse = onNavigateToBrowse,
                    onDuplicates = onNavigateToDuplicates,
                )
            }

            if (state.isEmpty) {
                item("empty") {
                    EmptyState(
                        title = "No screenshots indexed yet",
                        message = "Tap Scan now to discover the screenshots already on this device. " +
                            "Indexing runs in the background.",
                    )
                }
            } else {
                item("recent-header") {
                    SectionHeader("Recent screenshots")
                }
                items(recent, key = { it.id }) { screenshot ->
                    ScreenshotRow(
                        screenshot = screenshot,
                        onClick = onOpenScreenshot,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(state: ObserveIndexingStatsUseCase.UiState) {
    val stats = state.stats
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = formatCount(stats.total),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = if (stats.total == 1) "screenshot indexed" else "screenshots indexed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatColumn(formatCount(stats.completed), "Text read")
                StatColumn(formatCount(stats.pending), "Queued")
                StatColumn(formatCount(stats.duplicateGroups), "Duplicate sets")
            }
            if (stats.failed > 0) {
                Text(
                    text = "${formatCount(stats.failed)} could not be read and can be retried.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionRow(
    onScanNow: () -> Unit,
    onSearch: () -> Unit,
    onBrowse: () -> Unit,
    onDuplicates: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Button(onClick = onScanNow, modifier = Modifier.fillMaxWidth()) {
            Text("Scan now")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = onSearch,
                modifier = Modifier.weight(1f),
            ) { Text("Search") }
            androidx.compose.material3.OutlinedButton(
                onClick = onBrowse,
                modifier = Modifier.weight(1f),
            ) { Text("All") }
            androidx.compose.material3.OutlinedButton(
                onClick = onDuplicates,
                modifier = Modifier.weight(1f),
            ) { Text("Duplicates") }
        }
    }
}

/** Thousands separators without pulling in a locale-formatting dependency. */
private fun formatCount(value: Int): String =
    if (value < 1000) value.toString()
    else value.toString().reversed().chunked(3).joinToString(",").reversed()

private const val RECENT_LIMIT = 12
