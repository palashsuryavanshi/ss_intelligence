package com.ssintelligence.app.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.graph.GraphEntityType
import com.ssintelligence.app.graph.TopEntity
import com.ssintelligence.app.ui.common.DateFormats
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Entity explorer (§52) and entity pages (§17, §53).
 *
 * Only entity types actually detected in the library are shown, each with its
 * screenshot count. An entity page answers "everything about Pixel 9a": its
 * screenshots, the prices seen with it (in screenshot-date order, never
 * presented as market data), its websites and its categories.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenEntity: (Long) -> Unit,
    viewModel: ExploreViewModel = viewModel(factory = ExploreViewModel.Factory(locator)),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (groups.isEmpty()) {
            EmptyState(
                title = "Nothing filed yet",
                message = "Products, websites and other entities appear here once screenshots " +
                    "are indexed.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { group ->
                item("header-${group.type}") {
                    Text(
                        text = group.type.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = SsColors.NavyAccent,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                item("chips-${group.type}") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.entities.forEach { entity ->
                            AssistChip(
                                onClick = { onOpenEntity(entity.entity.id) },
                                label = {
                                    Text(
                                        "${entity.entity.displayName} · ${entity.screenshotCount}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityScreen(
    locator: ServiceLocator,
    entityId: Long,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: EntityViewModel = viewModel(factory = EntityViewModel.Factory(locator, entityId)),
) {
    val page by viewModel.page.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = page?.entity?.displayName ?: "Entity",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val current = page
        if (current == null) {
            EmptyState(
                title = "Entity not found",
                message = "It may have been removed when its last screenshot was deleted.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("summary") {
                Text(
                    text = "${current.screenshotIds.size} screenshot${if (current.screenshotIds.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (current.pricesSeen.isNotEmpty()) {
                item("prices-header") { SectionHeader("Prices seen in your screenshots") }
                items(current.pricesSeen) { price ->
                    Text(
                        text = "• ${price.label} — ${DateFormats.formatDate(price.seenAtSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (current.websites.isNotEmpty()) {
                item("sites-header") { SectionHeader("Websites") }
                item("sites") {
                    Text(
                        text = current.websites.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (current.categories.isNotEmpty()) {
                item("cats-header") { SectionHeader("Categories") }
                item("cats") {
                    Text(
                        text = current.categories.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item("shots-header") { SectionHeader("Screenshots") }
            items(current.shots, key = { it.id }) { screenshot ->
                ScreenshotRow(screenshot = screenshot, onClick = { onOpenScreenshot(screenshot.id) })
            }
        }
    }
}

class ExploreViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    data class EntityGroup(
        val type: GraphEntityType,
        val entities: List<TopEntity>,
    )

    private val _groups = MutableStateFlow(emptyList<EntityGroup>())
    val groups: StateFlow<List<EntityGroup>> = _groups.asStateFlow()

    init {
        viewModelScope.launch {
            val repository = locator.screenshotRepository
            val wanted = listOf(
                GraphEntityType.PRODUCT,
                GraphEntityType.COMPANY,
                GraphEntityType.WEBSITE,
                GraphEntityType.CATEGORY,
                GraphEntityType.BOOKING,
                GraphEntityType.ORDER,
            )
            _groups.value = wanted.mapNotNull { type ->
                val entities = runCatching {
                    repository.topEntities(listOf(type), minCount = 2, limit = 12)
                }.getOrDefault(emptyList())
                if (entities.isEmpty()) null else EntityGroup(type, entities)
            }
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExploreViewModel(locator) as T
    }
}

class EntityViewModel(
    private val locator: ServiceLocator,
    private val entityId: Long,
) : ViewModel() {

    data class Page(
        val entity: com.ssintelligence.app.graph.GraphEntity,
        val screenshotIds: List<Long>,
        val pricesSeen: List<com.ssintelligence.app.graph.PricePoint>,
        val websites: List<String>,
        val categories: List<String>,
        val shots: List<com.ssintelligence.app.domain.model.Screenshot>,
    )

    private val _page = MutableStateFlow<Page?>(null)
    val page: StateFlow<Page?> = _page.asStateFlow()

    init {
        viewModelScope.launch {
            val repository = locator.screenshotRepository
            val entityPage = repository.entityPage(entityId)
            if (entityPage == null) {
                _page.value = null
                return@launch
            }
            val shots = entityPage.screenshotIds.mapNotNull { repository.getById(it) }
            _page.value = Page(
                entity = entityPage.entity,
                screenshotIds = entityPage.screenshotIds,
                pricesSeen = entityPage.pricesSeen,
                websites = entityPage.websites,
                categories = entityPage.categories,
                shots = shots,
            )
        }
    }

    class Factory(
        private val locator: ServiceLocator,
        private val entityId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EntityViewModel(locator, entityId) as T
    }
}
