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
    onNavigateToTimeline: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToExplore: () -> Unit = {},
    onNavigateToAssistant: () -> Unit = {},
    onOpenEntity: (Long) -> Unit = {},
    onNavigateToInsights: () -> Unit = {},
    onNavigateToCleanup: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onOpenScreenshot: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(locator)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val suggestion by viewModel.suggestion.collectAsStateWithLifecycle()
    val important by viewModel.important.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
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
                    onTimeline = onNavigateToTimeline,
                    onCollections = onNavigateToCollections,
                    onExplore = onNavigateToExplore,
                    onAssistant = onNavigateToAssistant,
                    onInsights = onNavigateToInsights,
                    onCleanup = onNavigateToCleanup,
                    onPrivacy = onNavigateToPrivacy,
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
                suggestion?.let { item ->
                    item("suggestion") {
                        SuggestionCard(
                            label = item.label,
                            count = item.count,
                            onClick = { onOpenEntity(item.entityId) },
                        )
                    }
                }
                if (groups.isNotEmpty()) {
                    item("collections-header") {
                        SectionHeader("Smart Collections")
                    }
                    items(groups, key = { "group-${it.id}" }) { group ->
                        SmartGroupCard(
                            group = group,
                            locator = locator,
                            onOpenScreenshot = onOpenScreenshot,
                        )
                    }
                }
                if (important.isNotEmpty()) {
                    item("important-header") {
                        SectionHeader("Important")
                    }
                    items(important.take(4), key = { "important-${it.id}" }) { screenshot ->
                        ScreenshotRow(
                            screenshot = screenshot,
                            onClick = onOpenScreenshot,
                        )
                    }
                }
                if (insights.isNotEmpty()) {
                    item("insights-header") {
                        SectionHeader("Insights")
                    }
                    items(insights, key = { "insight-${it.id}" }) { insight ->
                        InsightRow(insight = insight)
                    }
                }
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
    onTimeline: () -> Unit,
    onCollections: () -> Unit,
    onExplore: () -> Unit,
    onAssistant: () -> Unit,
    onInsights: () -> Unit,
    onCleanup: () -> Unit,
    onPrivacy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Button(onClick = onScanNow, modifier = Modifier.fillMaxWidth()) {
            Text("Scan now")
        }
        // The assistant is the headline feature: it talks to the whole library.
        androidx.compose.material3.Button(onClick = onAssistant, modifier = Modifier.fillMaxWidth()) {
            Text("Ask your screenshots")
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
        // Second row, not bottom navigation: these are destinations, not tabs,
        // and the hierarchy stays Home → place → back (§51).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = onTimeline,
                modifier = Modifier.weight(1f),
            ) { Text("Timeline") }
            androidx.compose.material3.OutlinedButton(
                onClick = onCollections,
                modifier = Modifier.weight(1f),
            ) { Text("Collections") }
            androidx.compose.material3.OutlinedButton(
                onClick = onExplore,
                modifier = Modifier.weight(1f),
            ) { Text("Explore") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = onInsights,
                modifier = Modifier.weight(1f),
            ) { Text("Insights") }
            androidx.compose.material3.OutlinedButton(
                onClick = onCleanup,
                modifier = Modifier.weight(1f),
            ) { Text("Cleanup") }
            androidx.compose.material3.OutlinedButton(
                onClick = onPrivacy,
                modifier = Modifier.weight(1f),
            ) { Text("Privacy") }
        }
    }
}

/** Thousands separators without pulling in a locale-formatting dependency. */
private fun formatCount(value: Int): String =
    if (value < 1000) value.toString()
    else value.toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * One quiet in-app suggestion (§35): "You have N screenshots about X."
 *
 * Shown only when an entity covers enough screenshots to be worth opening,
 * and never as a notification. Tapping opens the entity page.
 */
@Composable
private fun SuggestionCard(
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        onClick = onClick,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "You have $count screenshots about $label.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "View collection →",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * One smart collection (§24).
 *
 * Shows the cover (newest member), the honest label, and the member count.
 * Tapping opens the cover; the full group is a search away, so the card stays
 * small. Groups with fewer than three members are never built, so every card
 * here earned its place.
 */
@Composable
private fun SmartGroupCard(
    group: com.ssintelligence.app.semantic.SmartGroup,
    locator: ServiceLocator,
    onOpenScreenshot: (Long) -> Unit,
) {
    val cover by locator.screenshotRepository.observeScreenshot(group.coverId)
        .collectAsStateWithLifecycle(initialValue = null)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        onClick = { onOpenScreenshot(group.coverId) },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cover?.let {
                com.ssintelligence.app.ui.common.ScreenshotThumbnail(
                    screenshot = it,
                    modifier = com.ssintelligence.app.ui.common.thumbnailModifier(),
                    contentDescription = null,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "${group.size} screenshots",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One insight row (§51): a locally derived, reviewable observation.
 */
@Composable
private fun InsightRow(insight: HomeViewModel.Insight) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = insight.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val RECENT_LIMIT = 12
