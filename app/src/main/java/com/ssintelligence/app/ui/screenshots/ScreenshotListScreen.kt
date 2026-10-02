package com.ssintelligence.app.ui.screenshots

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.SearchFilter
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow

/**
 * Browsable list of indexed screenshots (§23).
 *
 * Long-press enters a selection; with a selection active the top bar offers
 * "Ask about these", which hands the ids to the assistant (§15). Selection is
 * transient UI state — it never writes to the index.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScreenshotListScreen(
    locator: ServiceLocator,
    onOpenScreenshot: (Long) -> Unit,
    onAskSelection: (List<Long>) -> Unit = {},
    onCompareSelection: (List<Long>) -> Unit = {},
    viewModel: ScreenshotListViewModel = viewModel(factory = ScreenshotListViewModel.Factory(locator)),
) {
    val screenshots by viewModel.screenshots.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var showCollectDialog by remember { mutableStateOf(false) }
    var collectName by remember { mutableStateOf("") }
    val selectionMode = selection.isNotEmpty()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "${selection.size} selected" else "Screenshots",
                    )
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                        IconButton(
                            onClick = {
                                onAskSelection(selection.toList())
                                selection = emptySet()
                            },
                            enabled = selection.size >= 2,
                        ) {
                            Icon(Icons.Filled.Chat, contentDescription = "Ask about these screenshots")
                        }
                        // Compare and collect apply only to a multi-selection —
                        // they are never shown for a single screenshot (§25).
                        if (selection.size >= 2) {
                            IconButton(
                                onClick = {
                                    onCompareSelection(selection.toList())
                                    selection = emptySet()
                                },
                            ) {
                                Icon(Icons.Filled.CompareArrows, contentDescription = "Compare selected screenshots")
                            }
                            IconButton(
                                onClick = { showCollectDialog = true },
                            ) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Create collection from selection")
                            }
                        }
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
            if (!selectionMode) {
                item("filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BROWSE_FILTERS) { entry ->
                            FilterChip(
                                selected = entry.filter == filter,
                                onClick = { viewModel.onFilterChange(entry.filter) },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                }
            }

            if (screenshots.isEmpty()) {
                item("empty") {
                    EmptyState(
                        title = "Nothing here yet",
                        message = "Screenshots appear once they have been indexed.",
                    )
                }
            } else {
                items(screenshots, key = { it.id }) { screenshot ->
                    val selected = screenshot.id in selection
                    ScreenshotRow(
                        screenshot = screenshot,
                        onClick = { id ->
                            if (selectionMode) {
                                selection = if (selected) selection - id else selection + id
                            } else {
                                onOpenScreenshot(id)
                            }
                        },
                        selected = selected,
                        onLongClick = { id -> selection = selection + id },
                    )
                }
            }
        }
    }

    if (showCollectDialog) {
        AlertDialog(
            onDismissRequest = { showCollectDialog = false },
            title = { Text("Create collection from ${selection.size} screenshots?") },
            text = {
                OutlinedTextField(
                    value = collectName,
                    onValueChange = { collectName = it },
                    label = { Text("Collection name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selection.toList()
                        val name = collectName.ifBlank { "Selection" }
                        scope.launch {
                            val id = locator.screenshotRepository.createCollection(name)
                            for (shotId in ids) {
                                runCatching {
                                    locator.screenshotRepository.addToCollection(id, shotId)
                                }
                            }
                        }
                        selection = emptySet()
                        collectName = ""
                        showCollectDialog = false
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCollectDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private data class BrowseFilter(val filter: SearchFilter, val label: String)

private val BROWSE_FILTERS = listOf(
    BrowseFilter(SearchFilter.ALL, "All"),
    BrowseFilter(SearchFilter.URLS, "Links"),
    BrowseFilter(SearchFilter.PRICES, "Prices"),
    BrowseFilter(SearchFilter.DATES, "Dates"),
    BrowseFilter(SearchFilter.PHONES, "Phones"),
    BrowseFilter(SearchFilter.DUPLICATES, "Duplicates"),
)
