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
    onNavigateToInsights: () -> Unit = {},
    onNavigateToCleanup: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {},
    onNavigateToExpenses: () -> Unit = {},
    onNavigateToActions: () -> Unit = {},
    onNavigateToAutomation: () -> Unit = {},
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
    val processingMode by viewModel.processingMode.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val visualProgress by viewModel.visualRebuildProgress.collectAsStateWithLifecycle()
    val automationEnabled by viewModel.automationEnabled.collectAsStateWithLifecycle()
    val contextActionsEnabled by viewModel.contextActionsEnabled.collectAsStateWithLifecycle()
    val expenseExtractionEnabled by viewModel.expenseExtractionEnabled.collectAsStateWithLifecycle()
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

            SettingsHeader("Intelligence processing")
            ProcessingModeOptions(
                selected = processingMode,
                onSelect = viewModel::onProcessingModeChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Visual intelligence")
            VisualIntelligenceSection(
                visualProgress = visualProgress,
                onRebuild = viewModel::onBuildVisualIndex,
                onClearVisuals = viewModel::onClearVisualIndex,
                onClearCategories = viewModel::onClearAutoCategories,
                onClearGraph = viewModel::onClearGraph,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Smart organization")
            SmartOrganizationSection(
                onInsights = onNavigateToInsights,
                onCleanup = onNavigateToCleanup,
                onPrivacy = onNavigateToPrivacy,
                onRebuildAutonomous = viewModel::onRebuildAutonomous,
                onClearAutonomous = viewModel::onClearAutonomous,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Actions & Automation")
            ActionsAutomationSection(
                automationEnabled = automationEnabled,
                onAutomationChange = viewModel::onAutomationEnabledChange,
                contextActionsEnabled = contextActionsEnabled,
                onContextActionsChange = viewModel::onContextActionsEnabledChange,
                expenseExtractionEnabled = expenseExtractionEnabled,
                onExpenseExtractionChange = viewModel::onExpenseExtractionEnabledChange,
                onTasks = onNavigateToTasks,
                onExpenses = onNavigateToExpenses,
                onActions = onNavigateToActions,
                onAutomation = onNavigateToAutomation,
                onClearActionData = viewModel::onClearActionData,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Storage usage")
            StorageSection(storage = storage)
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

/**
 * Actions and automation controls (§71).
 *
 * Each feature works independently and can be turned off. Critical and
 * security-sensitive actions still require confirmation regardless of these
 * preferences — the toggles govern suggestions and automation, never safety.
 */
@Composable
private fun ActionsAutomationSection(
    automationEnabled: Boolean,
    onAutomationChange: (Boolean) -> Unit,
    contextActionsEnabled: Boolean,
    onContextActionsChange: (Boolean) -> Unit,
    expenseExtractionEnabled: Boolean,
    onExpenseExtractionChange: (Boolean) -> Unit,
    onTasks: () -> Unit,
    onExpenses: () -> Unit,
    onActions: () -> Unit,
    onAutomation: () -> Unit,
    onClearActionData: () -> Unit,
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleRow(
            label = "Automation",
            description = "Let rules organize new screenshots automatically.",
            checked = automationEnabled,
            onCheckedChange = onAutomationChange,
        )
        ToggleRow(
            label = "Contextual actions",
            description = "Suggest actions from detected content on detail pages.",
            checked = contextActionsEnabled,
            onCheckedChange = onContextActionsChange,
        )
        ToggleRow(
            label = "Expense extraction",
            description = "Offer to save receipts as expenses.",
            checked = expenseExtractionEnabled,
            onCheckedChange = onExpenseExtractionChange,
        )
        TextButton(onClick = onTasks) { Text("View tasks") }
        TextButton(onClick = onExpenses) { Text("View expenses") }
        TextButton(onClick = onActions) { Text("Action history") }
        TextButton(onClick = onAutomation) { Text("Manage automation") }
        TextButton(onClick = { showClearConfirm = true }) { Text("Clear action data") }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear action data?") },
            text = {
                Text(
                    "Removes reminders, expenses, action history and automation rules. " +
                        "Your screenshots and index are untouched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearActionData()
                        showClearConfirm = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Smart organization controls (§60).
 *
 * Each feature is independent and can be turned off. Nothing here deletes a
 * screenshot: the cleanup center only ever suggests, and deletion always
 * requires explicit confirmation.
 */
@Composable
private fun SmartOrganizationSection(
    onInsights: () -> Unit,
    onCleanup: () -> Unit,
    onPrivacy: () -> Unit,
    onRebuildAutonomous: () -> Unit,
    onClearAutonomous: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Topics, sessions, events, importance and lifecycle are " +
                "derived automatically from your screenshots. Nothing is deleted " +
                "without your confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onInsights) { Text("View insights") }
        TextButton(onClick = onCleanup) { Text("Review cleanup suggestions") }
        TextButton(onClick = onPrivacy) { Text("Privacy center") }
        TextButton(onClick = onRebuildAutonomous) { Text("Rebuild organization") }
        TextButton(onClick = onClearAutonomous) { Text("Clear generated organization") }
    }
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
 * Background processing policy (§62).
 *
 * Only governs expensive background embedding. Browsing and search work
 * identically in every mode.
 */
@Composable
private fun ProcessingModeOptions(
    selected: com.ssintelligence.app.domain.repository.ProcessingMode,
    onSelect: (com.ssintelligence.app.domain.repository.ProcessingMode) -> Unit,
) {
    Column {
        com.ssintelligence.app.domain.repository.ProcessingMode.entries.forEach { mode ->
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
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = when (mode) {
                            com.ssintelligence.app.domain.repository.ProcessingMode.AUTOMATIC ->
                                "Catch-up work runs whenever"

                            com.ssintelligence.app.domain.repository.ProcessingMode.CHARGING_ONLY ->
                                "Catch-up work waits for charging"

                            com.ssintelligence.app.domain.repository.ProcessingMode.MANUAL ->
                                "Only explicit taps run the builders"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Visual index controls (§43).
 *
 * No model to install or remove: visual analysis is built in like the text
 * index. What can be managed is the derived data — rebuild it, or delete
 * pieces of it independently (§64). Deleting never touches screenshots, OCR
 * text, or extracted information.
 */
@Composable
private fun VisualIntelligenceSection(
    visualProgress: Int,
    onRebuild: () -> Unit,
    onClearVisuals: () -> Unit,
    onClearCategories: () -> Unit,
    onClearGraph: () -> Unit,
) {
    var showClearVisuals by remember { mutableStateOf(false) }
    var showClearCategories by remember { mutableStateOf(false) }
    var showClearGraph by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetadataRow("Visual model", "built-in perceptual hash + rules")
        MetadataRow("Model size", "no download needed")
        if (visualProgress > 0) {
            Text(
                text = "Analyzing images… $visualProgress done",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = onRebuild) { Text("Build visual index") }
        TextButton(onClick = { showClearVisuals = true }) { Text("Delete image embeddings") }
        TextButton(onClick = { showClearCategories = true }) { Text("Delete automatic categories") }
        TextButton(onClick = { showClearGraph = true }) { Text("Delete knowledge graph") }

        Text(
            text = "Visual analysis, automatic categories and the knowledge graph are all " +
                "derived from the extracted data already on this device, so \"Build visual " +
                "index\" refills any of them at any time. User collections and category " +
                "corrections are never deleted by the actions below — only derived data goes, " +
                "and screenshots, text and search keep working regardless.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showClearVisuals) {
        ConfirmDelete(
            title = "Delete image embeddings?",
            message = "Visual similarity and palette search stop working until the index " +
                "is rebuilt. Screenshots, text and text search are unaffected.",
            onDismiss = { showClearVisuals = false },
            onConfirm = {
                onClearVisuals()
                showClearVisuals = false
            },
        )
    }
    if (showClearCategories) {
        ConfirmDelete(
            title = "Delete automatic categories?",
            message = "Classifier-assigned categories are removed. Categories you chose " +
                "yourself stay.",
            onDismiss = { showClearCategories = false },
            onConfirm = {
                onClearCategories()
                showClearCategories = false
            },
        )
    }
    if (showClearGraph) {
        ConfirmDelete(
            title = "Delete knowledge graph?",
            message = "Entities and relationships are removed. Entity pages and " +
                "entity search stop working until \"Build visual index\" refills them " +
                "from the extracted data already on this device.",
            onDismiss = { showClearGraph = false },
            onConfirm = {
                onClearGraph()
                showClearGraph = false
            },
        )
    }
}

@Composable
private fun ConfirmDelete(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Measured storage breakdown (§63).
 *
 * Every row is page accounting from the database itself — except models, which
 * cost zero bytes because they are built in. When accounting fails the section
 * says so instead of guessing.
 */
@Composable
private fun StorageSection(storage: com.ssintelligence.app.domain.repository.StorageBreakdown?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (storage == null) {
            Text(
                text = "Measuring…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        if (!storage.measured) {
            Text(
                text = "Per-component accounting is unavailable on this device. " +
                    "Total database size is shown under Storage above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        MetadataRow("Screenshots + extracted data", formatBytes(storage.screenshotsBytes))
        MetadataRow("Text search index", formatBytes(storage.ocrIndexBytes))
        MetadataRow("Text vectors", formatBytes(storage.textVectorBytes))
        MetadataRow("Image data", formatBytes(storage.imageVectorBytes))
        MetadataRow("Knowledge graph + categories", formatBytes(storage.graphBytes))
        MetadataRow("Search history", formatBytes(storage.historyBytes))
        MetadataRow("Models", "built in (0 B)")
        MetadataRow("Database total", formatBytes(storage.databaseBytes))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unitIndex])
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
 * Settings that are still not implemented. They are shown disabled with an
 * explanation rather than omitted, so the roadmap is visible and no control is a
 * no-op surprise.
 *
 * Three Phase 1 placeholders left this list as their phases landed: background
 * processing is now the `Processing mode` radio group above, and near-duplicate
 * detection is the Similar section on the Duplicates screen. Shipping a
 * disabled switch for a feature that now exists would be a lie in the other
 * direction, so they are gone rather than greyed out.
 */
@Composable
private fun DeferredSettings() {
    Column(Modifier.padding(top = 8.dp)) {
        DeferredRow("Automatic screenshot deletion")
        DeferredRow("Face grouping in photos")
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
