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
    val context = LocalContext.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var showRebuildConfirm by remember { mutableStateOf(false) }

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
