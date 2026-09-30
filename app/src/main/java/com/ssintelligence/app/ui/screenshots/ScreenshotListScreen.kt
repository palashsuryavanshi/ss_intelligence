package com.ssintelligence.app.ui.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.SearchFilter
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow

/** Browsable list of indexed screenshots (§23). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotListScreen(
    locator: ServiceLocator,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: ScreenshotListViewModel = viewModel(factory = ScreenshotListViewModel.Factory(locator)),
) {
    val screenshots by viewModel.screenshots.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Screenshots") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            if (screenshots.isEmpty()) {
                item("empty") {
                    EmptyState(
                        title = "Nothing here yet",
                        message = "Screenshots appear once they have been indexed.",
                    )
                }
            } else {
                items(screenshots, key = { it.id }) { screenshot ->
                    ScreenshotRow(screenshot = screenshot, onClick = onOpenScreenshot)
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
