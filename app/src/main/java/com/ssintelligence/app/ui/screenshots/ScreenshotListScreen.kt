package com.ssintelligence.app.ui.screenshots

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    viewModel: ScreenshotListViewModel = viewModel(factory = ScreenshotListViewModel.Factory(locator)),
) {
    val screenshots by viewModel.screenshots.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selection.isNotEmpty()

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
