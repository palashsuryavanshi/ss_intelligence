package com.ssintelligence.app.ui.search

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.IndexingProgress
import com.ssintelligence.app.search.ContentType
import com.ssintelligence.app.search.SearchQuery
import com.ssintelligence.app.search.SortMode
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.IndexingProgressCard

/**
 * The search experience (§25, §26, §28, §29, §30, §31).
 *
 * A person types a sentence; the screen shows what the engine understood, the
 * results, and — when a constraint had to be dropped to produce them — a plain
 * statement of that fact.
 *
 * List position lives in `rememberLazyListState`, which is saved per navigation
 * entry, so opening a result and coming back lands on the same row (§41).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    locator: ServiceLocator,
    onOpenScreenshot: (Long) -> Unit,
    /** Debug builds only; see the note on the title composable. */
    onOpenSearchDebug: (() -> Unit)? = null,
    /** Pre-filled query for "More from X" actions. Applied once on entry. */
    presetQuery: String = "",
    /** Search-by-image target, owned by the navigation host. */
    visualQueryId: Long? = null,
    /** Opens the image picker (§7). */
    onPickImage: () -> Unit = {},
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory(locator)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val manual by viewModel.manual.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val pinnedVisual by viewModel.visualQuery.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val recent by viewModel.recentSearches.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    var showFilters by rememberSaveable { mutableStateOf(false) }

    // A preset query (from "More from X") fills the box once on entry. Keyed
    // on the preset itself so navigating back and forth re-applies it.
    androidx.compose.runtime.LaunchedEffect(presetQuery) {
        if (presetQuery.isNotBlank()) viewModel.onQueryChange(presetQuery)
    }

    // The pinned image arrives from the picker through the host. Keyed on the
    // id so re-picking the same image is a no-op and clearing works.
    androidx.compose.runtime.LaunchedEffect(visualQueryId) {
        viewModel.onVisualQueryChange(visualQueryId)
    }

    // A new query means a new result set: start at the top rather than keeping
    // an offset that no longer refers to anything.
    LaunchedEffect(query, filters, manual, sort) {
        listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // The search inspector lives behind a long press on the
                    // title, and only in debug builds: a release build has no
                    // click handler and no registered route at all, so there is
                    // nothing for a user to find (§46).
                    if (onOpenSearchDebug != null) {
                        Text(
                            text = "Search",
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = onOpenSearchDebug,
                                onLongClickLabel = "Open the search inspector",
                            ),
                        )
                    } else {
                        Text("Search")
                    }
                },
                actions = {
                    SortMenu(selected = sort, onSelect = viewModel::onSortChange)
                    IconButton(onClick = { showFilters = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Refine results")
                    }
                    IconButton(onClick = onPickImage) {
                        Icon(Icons.Filled.Image, contentDescription = "Search by image")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics {
                        contentDescription =
                            "Search your screenshots. Prices, dates, links and phone " +
                                "numbers in a sentence are understood."
                    },
                placeholder = { Text("Find in screenshots…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = viewModel::onClearQuery) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                    keyboardType = KeyboardType.Text,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = {
                        viewModel.onSubmit()
                        keyboard?.hide()
                    },
                ),
            )

            FilterRow(selected = filters, onToggle = viewModel::onFilterToggle)

            pinnedVisual?.let { pinnedId ->
                PinnedImageRow(
                    locator = locator,
                    screenshotId = pinnedId,
                    onClear = { viewModel.onVisualQueryChange(null) },
                )
            }

            when (val current = state) {
                SearchUiState.Idle -> IdleContent(
                    suggestions = suggestions.map { it.text },
                    recent = recent,
                    onPick = viewModel::onSuggestionSelected,
                    indexing = indexing,
                    onStopIndexing = { locator.indexingScheduler.cancel() },
                )

                SearchUiState.Searching -> BusyContent("Searching your screenshots…")

                is SearchUiState.Results -> ResultsContent(
                    state = current,
                    listState = listState,
                    onOpenScreenshot = onOpenScreenshot,
                    pinnedVisual = pinnedVisual,
                )

                is SearchUiState.NoResults -> NoResultsContent(
                    parsed = current.parsed,
                    suggestions = suggestions.map { it.text },
                    onPick = viewModel::onSuggestionSelected,
                )

                is SearchUiState.Error -> EmptyState(
                    title = "Search failed",
                    message = current.message,
                )

                is SearchUiState.DatabaseUnavailable -> EmptyState(
                    title = "Index unavailable",
                    message = current.message,
                )
            }
        }
    }

    if (showFilters) {
        SearchFilterSheet(
            current = manual,
            contentTypes = filters,
            onApply = { newManual, newTypes ->
                viewModel.onManualFiltersChange(newManual)
                viewModel.onContentTypesChange(newTypes)
                showFilters = false
            },
            onDismiss = { showFilters = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleContent(
    suggestions: List<String>,
    recent: List<String>,
    onPick: (String) -> Unit,
    indexing: IndexingProgress,
    onStopIndexing: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        if (indexing.total > 0 && indexing.processed < indexing.total) {
            IndexingProgressCard(
                processed = indexing.processed,
                total = indexing.total,
                onStop = onStopIndexing,
            )
        }

        if (suggestions.isNotEmpty()) {
            SearchSectionLabel("Suggestions")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { text ->
                    SuggestionChip(text = text, onClick = { onPick(text) })
                }
            }
        }

        if (recent.isNotEmpty()) {
            SearchSectionLabel("Recent searches")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { text ->
                    SuggestionChip(text = text, onClick = { onPick(text) })
                }
            }
        }

        if (suggestions.isEmpty() && recent.isEmpty()) {
            EmptyState(
                title = "Search your screenshots in plain words",
                message = "Try “Find the screenshot where I saw Pixel 9a for ₹39,999”, " +
                    "“screenshots from last week”, “show duplicate screenshots”, or " +
                    "“find the screenshot containing 9876543210”. Everything runs on this device.",
            )
        }
    }
}

@Composable
private fun BusyContent(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ResultsContent(
    state: SearchUiState.Results,
    listState: LazyListState,
    onOpenScreenshot: (Long) -> Unit,
    pinnedVisual: Long?,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = SearchListPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("parsed") {
            // An empty query describes itself as "everything", which is both
            // wrong and alarming next to a pinned image: the search is not a
            // browse. Say what actually narrowed it.
            val description = if (state.parsed.isEmpty && pinnedVisual != null) {
                "screenshots that look like the one you picked"
            } else {
                state.parsed.describe()
            }
            ParsedQueryBanner(description = description)
        }

        if (state.relaxed) {
            item("relaxed") {
                RelaxationBanner(message = state.response.relaxation.userMessage.orEmpty())
            }
        }

        item("count") {
            ResultCountLine(state = state)
        }

        items(state.results, key = { it.screenshot.id }) { result ->
            SearchResultCard(
                result = result,
                onClick = { onOpenScreenshot(result.screenshot.id) },
            )
        }
    }
}

/**
 * Result count with the search-mode indicator (§46).
 *
 * "Meaning-based results" appears only when the semantic index actually
 * contributed; otherwise the honest label is "Text matches". The distinction
 * is the transparency the spec asks for: the user should know which half of
 * the engine answered.
 *
 * A visual-only search is a third mode. Labelling its results "Text matches"
 * would be a straightforward lie — nothing about the words was considered —
 * so results that carry a visual reason say so instead.
 */
@Composable
private fun ResultCountLine(state: SearchUiState.Results) {
    val visualOnly = state.results.isNotEmpty() &&
        state.results.all { result -> result.matches.any { it.kind == com.ssintelligence.app.search.MatchKind.VISUAL } }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${state.results.size} result${if (state.results.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                visualOnly -> "Similar-looking screenshots"
                state.response.semanticUsed -> "Meaning-based results"
                else -> "Text matches"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Empty results (§28).
 *
 * A bare "No results" is a dead end. The concepts the engine understood are
 * offered back as one-tap alternatives so the user can drop a constraint
 * instead of retyping the sentence. Every alternative is a subset of what was
 * actually typed — nothing is invented.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoResultsContent(
    parsed: SearchQuery,
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ParsedQueryBanner(description = parsed.describe())
        SearchSectionLabel("Nothing matched all of that")
        val alternatives = buildAlternatives(parsed)
        if (alternatives.isEmpty() && suggestions.isEmpty()) {
            Text(
                text = "No screenshots matched. Try fewer words, or clear the filters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Text(
                text = "Try one of these instead:",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (alternatives + suggestions).distinct().take(6).forEach { text ->
                    SuggestionChip(text = text, onClick = { onPick(text) })
                }
            }
        }
    }
}

/**
 * Alternatives derived from the parsed query, least constraint removed first.
 */
private fun buildAlternatives(query: SearchQuery): List<String> {
    val out = mutableListOf<String>()
    if (query.prices.isNotEmpty() && query.ftsTerms.isNotEmpty()) {
        out += (query.phrases + query.textTerms).joinToString(" ")
    }
    if (query.prices.isNotEmpty() && query.ftsTerms.isEmpty()) {
        out += query.prices.first().display()
    }
    query.dateFilters.firstOrNull()?.let { out += it.label }
    query.urls.firstOrNull()?.let { out += it }
    return out.filter { it.isNotBlank() }
}

@Composable
private fun FilterRow(
    selected: Set<ContentType>,
    onToggle: (ContentType) -> Unit,
) {
    val chips = listOf(
        ContentType.PRICES to "Prices",
        ContentType.DATES to "Dates",
        ContentType.URLS to "Links",
        ContentType.PHONES to "Numbers",
        ContentType.OTPS to "Codes",
        ContentType.DUPLICATES to "Duplicates",
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("all") {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { selected.forEach(onToggle) },
                label = { Text("All") },
            )
        }
        items(chips) { (type, label) ->
            FilterChip(
                selected = type in selected,
                onClick = { onToggle(type) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SortMenu(selected: SortMode, onSelect: (SortMode) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected.label(), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label()) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun SortMode.label(): String = when (this) {
    SortMode.RELEVANCE -> "Relevance"
    SortMode.NEWEST -> "Newest"
    SortMode.OLDEST -> "Oldest"
}

/**
 * The pinned search-by-image target (§7).
 *
 * Shows the thumbnail so the user can see what "like this one" means, with an
 * explicit remove action. Text and image combine while both are present.
 */
@Composable
private fun PinnedImageRow(
    locator: ServiceLocator,
    screenshotId: Long,
    onClear: () -> Unit,
) {
    val screenshot by locator.screenshotRepository.observeScreenshot(screenshotId)
        .collectAsStateWithLifecycle(initialValue = null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        screenshot?.let {
            com.ssintelligence.app.ui.common.ScreenshotThumbnail(
                screenshot = it,
                modifier = Modifier.size(48.dp),
                contentDescription = "Search image: ${it.filename}",
            )
        }
        Text(
            text = "Finding shots that look like this",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Clear, contentDescription = "Remove search image")
        }
    }
}
