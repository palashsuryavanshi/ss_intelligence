package com.ssintelligence.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.IndexingScope
import com.ssintelligence.app.domain.model.ThemeMode
import com.ssintelligence.app.ui.common.DateFormats
import com.ssintelligence.app.ui.common.MetadataRow

/**
 * Settings (§34).
 *
 * Phase 1 ships the options that are actually implemented. Deferred settings
 * are listed as disabled with an explicit "coming later" note rather than
 * hidden, so the design intent is visible without shipping dead controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(locator)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val databaseSize by viewModel.databaseSizeBytes.collectAsStateWithLifecycle()
    val searchHistoryEnabled by viewModel.searchHistoryEnabled.collectAsStateWithLifecycle()
    val searchHistoryCount by viewModel.searchHistoryCount.collectAsStateWithLifecycle()
    val semanticEnabled by viewModel.semanticEnabled.collectAsStateWithLifecycle()
    val embeddedCount by viewModel.embeddedCount.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val rebuildProgress by viewModel.semanticRebuildProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var showRebuildConfirm by remember { mutableStateOf(false) }
    var showClearSearchHistory by remember { mutableStateOf(false) }
    var showClearSemanticIndex by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsHeader("Indexing")
            TextButton(
                onClick = {
                    if (state.isIndexing) viewModel.onStopIndexing() else viewModel.onScanNow()
                }
            ) {
                Text(if (state.isIndexing) "Stop indexing" else "Scan now")
            }
            MetadataRow("What gets indexed", state.scope.describe())
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Theme")
            ThemeOptions(selected = state.theme, onSelect = viewModel::onThemeChange)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Scope")
            ScopeOptions(selected = state.scope, onSelect = viewModel::onScopeChange)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Privacy")
            PrivacyFacts()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Search")
            SearchHistorySection(
                enabled = searchHistoryEnabled,
                storedQueries = searchHistoryCount,
                onEnabledChange = { viewModel.onSearchHistoryEnabledChange(it) },
                onClear = { viewModel.onClearSearchHistory() },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Meaning-based search")
            SemanticSearchSection(
                enabled = semanticEnabled,
                embedded = embeddedCount,
                completed = completedCount,
                rebuildProgress = rebuildProgress,
                modelInfo = viewModel.semanticModelInfo,
                onEnabledChange = viewModel::onSemanticEnabledChange,
                onRebuild = viewModel::onBuildSemanticIndex,
                onClear = { showClearSemanticIndex = true },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Privacy dashboard")
            PrivacyDashboard(
                searchHistoryEnabled = searchHistoryEnabled,
                storedQueries = searchHistoryCount,
                semanticEnabled = semanticEnabled,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Storage")
            MetadataRow("Indexed screenshots", state.indexedCount.toString())
            MetadataRow("Database size", DateFormats.formatFileSize(context, databaseSize))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Advanced")
            TextButton(onClick = { showRebuildConfirm = true }) { Text("Rebuild index") }
            TextButton(onClick = { showClearConfirm = true }) { Text("Clear index") }
            DeferredSettings()
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear the local index?") },
            text = {
                Text(
                    "This removes Screenshot Intelligence's index: the text it read and the " +
                        "information it extracted. Your screenshots stay exactly where they " +
                        "are and are not deleted, edited or moved. You can rebuild the index " +
                        "later with Scan now.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onClearIndex()
                        showClearConfirm = false
                    }
                ) { Text("Clear index") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showRebuildConfirm) {
        AlertDialog(
            onDismissRequest = { showRebuildConfirm = false },
            title = { Text("Rebuild the index?") },
            text = {
                Text(
                    "Every indexed screenshot will have its text read and its links, prices, " +
                        "dates, phone numbers and codes detected again. This runs entirely on " +
                        "this device and may take a while for a large library.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onRebuildIndex()
                        showRebuildConfirm = false
                    }
                ) { Text("Rebuild") }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildConfirm = false }) { Text("Cancel") }
            },
        )
    }
    if (showClearSearchHistory) {
        AlertDialog(
            onDismissRequest = { showClearSearchHistory = false },
            title = { Text("Clear search history?") },
            text = {
                Text(
                    "This deletes the $searchHistoryCount " +
                        (if (searchHistoryCount == 1) "query" else "queries") +
                        " stored on this device. Your screenshots and the index built from " +
                        "them are not affected, and nothing was ever sent anywhere.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onClearSearchHistory()
                        showClearSearchHistory = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearSearchHistory = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearSemanticIndex) {
        AlertDialog(
            onDismissRequest = { showClearSemanticIndex = false },
            title = { Text("Delete the meaning-based index?") },
            text = {
                Text(
                    "This removes the locally computed embeddings and automatic categories. " +
                        "Your screenshots, the text read from them, and everything extracted " +
                        "from that text stay exactly as they are — and text search keeps " +
                        "working unchanged.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onClearSemanticIndex()
                        showClearSemanticIndex = false
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearSemanticIndex = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun ThemeOptions(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                )
                Text(
                    text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ScopeOptions(selected: IndexingScope, onSelect: (IndexingScope) -> Unit) {
    Column {
        IndexingScope.entries.forEach { scope ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = scope == selected,
                    onClick = { onSelect(scope) },
                )
                Text(
                    text = scope.describe(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * Search history controls (§36, §37).
 *
 * The switch is off by default and turning it off deletes what was stored, so
 * "off" never means "hidden but kept". Queries that look like they carry a
 * one-time code are never stored even when the switch is on.
 */
@Composable
private fun SearchHistorySection(
    enabled: Boolean,
    storedQueries: Int,
    onEnabledChange: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Remember my searches", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Off by default. Keeps the last " +
                        "${com.ssintelligence.app.data.database.SearchHistoryDao.DEFAULT_LIMIT} " +
                        "queries on this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        MetadataRow(
            label = "Stored queries",
            value = if (enabled) storedQueries.toString() else "None",
        )

        if (storedQueries > 0) {
            TextButton(onClick = onClear) { Text("Clear search history") }
        }

        Text(
            text = "Searches that mention a one-time code are never saved, even with this " +
                "turned on. Suggestions and history are built only from data already on " +
                "this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Meaning-based search controls (§50).
 *
 * There is no model to download: the provider is built in, needs no network,
 * and works the moment the library is indexed. The switch only decides whether
 * the semantic half of ranking may run — turning it off leaves the Phase 2
 * deterministic engine exactly as it was.
 */
@Composable
private fun SemanticSearchSection(
    enabled: Boolean,
    embedded: Int,
    completed: Int,
    rebuildProgress: Int,
    modelInfo: com.ssintelligence.app.semantic.SemanticModelInfo,
    onEnabledChange: (Boolean) -> Unit,
    onRebuild: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Search by meaning", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Finds screenshots related to your words, not just containing them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        MetadataRow("Local model", "${modelInfo.name} v${modelInfo.version}")
        MetadataRow("Model size", modelInfo.sizeDescription)
        MetadataRow(
            label = "Screenshots with meaning data",
            value = if (completed > 0) "$embedded of $completed" else embedded.toString(),
        )
        if (rebuildProgress > 0) {
            Text(
                text = "Rebuilding meaning index… $rebuildProgress done",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRebuild) { Text("Build meaning index") }
            TextButton(onClick = onClear) { Text("Delete meaning index") }
        }

        Text(
            text = "The index is built from text already on this device, runs in the " +
                "background, and never leaves it. Deleting it keeps your screenshots, " +
                "their text, and text search untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The privacy dashboard (§41).
 *
 * Every row is a verifiable fact about this build, not a marketing claim: no
 * INTERNET permission, bundled OCR, local database, opt-in history, built-in
 * semantic index. If any of these stopped being true, the corresponding row
 * would be a lie — which is why each one names the mechanism, not just the
 * promise.
 */
@Composable
private fun PrivacyDashboard(
    searchHistoryEnabled: Boolean,
    storedQueries: Int,
    semanticEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FactRow("Text recognition: on-device (bundled model, no download)")
        FactRow("Meaning-based search: on-device (built-in, no download)")
        FactRow("Screenshot database: local, app-private storage")
        FactRow("Network uploads: none — the app holds no internet permission")
        FactRow(
            if (searchHistoryEnabled) {
                "Search history: on, $storedQueries queries stored locally"
            } else {
                "Search history: off, nothing stored"
            },
        )
        FactRow(
            if (semanticEnabled) {
                "Meaning index: enabled, local only"
            } else {
                "Meaning index: not used for ranking"
            },
        )
        Text(
            text = "One-time codes stay masked until you reveal them, and sensitive " +
                "screenshots never appear in suggestions or previews.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Facts about the privacy model, stated in the product itself rather than only
 * in a README (§2, §29).
 */
@Composable
private fun PrivacyFacts() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FactRow("Text recognition runs on this device")
        FactRow("No account, no sign-in")
        FactRow("No internet permission is requested at all")
        FactRow("Extracted information stays in a local database")
        FactRow("One-time codes are hidden until you reveal them")
        Text(
            text = "The app cannot send your screenshots anywhere: it does not even hold the " +
                "network permission.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FactRow(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Settings that Phase 1 deliberately does not implement (§21, §34). They are
 * shown disabled with an explanation rather than omitted, so the roadmap is
 * visible and no control is a no-op surprise.
 */
@Composable
private fun DeferredSettings() {
    Column(Modifier.padding(top = 8.dp)) {
        DeferredRow("Index automatically in the background")
        DeferredRow("Only process while charging")
        DeferredRow("Near-duplicate detection")
    }
}

@Composable
private fun DeferredRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Planned for a later release",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = false,
            onCheckedChange = { /* intentionally inert: not implemented in Phase 1 */ },
            enabled = false,
        )
    }
}

private fun IndexingScope.describe(): String = when (this) {
    IndexingScope.SCREENSHOTS_ONLY -> "Images found in screenshot folders"
    IndexingScope.ALL_IMAGES -> "Every image on this device"
}
