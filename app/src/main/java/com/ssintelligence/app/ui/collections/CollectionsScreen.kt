package com.ssintelligence.app.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.repository.CollectionInfo
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manual collections (§19) alongside automatic smart collections (§20, §21).
 *
 * A screenshot can belong to many collections; collections store references,
 * never images. Smart collections state their criteria, so "why is this here"
 * always has an answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: CollectionsViewModel = viewModel(factory = CollectionsViewModel.Factory(locator)),
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val smart by viewModel.smart.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val members by viewModel.members.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collections") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New collection")
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
            if (collections.isEmpty() && smart.isEmpty()) {
                item("empty") {
                    EmptyState(
                        title = "No collections yet",
                        message = "Create one for a project or a trip, or wait for smart " +
                            "collections to form once enough screenshots share a topic.",
                    )
                }
            }
            if (collections.isNotEmpty()) {
                item("mine-header") { SectionHeader("My collections") }
                items(collections, key = { "c-${it.id}" }) { collection ->
                    CollectionCard(
                        name = collection.name,
                        subtitle = "${collection.memberCount} screenshot${if (collection.memberCount == 1) "" else "s"}",
                        expanded = expandedId == collection.id,
                        onToggle = {
                            expandedId = if (expandedId == collection.id) null else collection.id
                            if (expandedId != null) viewModel.loadMembers(collection.id)
                        },
                        onDelete = { viewModel.delete(collection.id) },
                    )
                    if (expandedId == collection.id) {
                        members.forEach { screenshot ->
                            ScreenshotRow(
                                screenshot = screenshot,
                                onClick = { onOpenScreenshot(screenshot.id) },
                            )
                        }
                    }
                }
            }
            if (smart.isNotEmpty()) {
                item("smart-header") { SectionHeader("Smart collections") }
                items(smart, key = { "s-${it.id}" }) { group ->
                    CollectionCard(
                        name = group.label,
                        subtitle = "${group.size} screenshots · ${group.criteria.joinToString("; ")}",
                        expanded = expandedId == -group.id.hashCode().toLong(),
                        onToggle = {
                            expandedId = if (expandedId == -group.id.hashCode().toLong()) {
                                null
                            } else {
                                -group.id.hashCode().toLong()
                            }
                            if (expandedId != null) viewModel.loadSmartMembers(group.memberIds)
                        },
                        onDelete = null,
                    )
                    if (expandedId == -group.id.hashCode().toLong()) {
                        members.forEach { screenshot ->
                            ScreenshotRow(
                                screenshot = screenshot,
                                onClick = { onOpenScreenshot(screenshot.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.create(name)
                        creating = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CollectionCard(
    name: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDelete != null) {
                var confirming by rememberSaveable { mutableStateOf(false) }
                IconButton(onClick = { confirming = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete $name")
                }
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("Delete this collection?") },
                        text = {
                            Text(
                                "Only the collection is removed. Screenshots stay exactly " +
                                    "where they are.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onDelete()
                                    confirming = false
                                },
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirming = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}

class CollectionsViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    data class SmartCollection(
        val id: String,
        val label: String,
        val size: Int,
        val memberIds: List<Long>,
        /** Why each member is here, in plain words (§21). */
        val criteria: List<String>,
    )

    private val _collections = MutableStateFlow(emptyList<CollectionInfo>())
    val collections: StateFlow<List<CollectionInfo>> = _collections.asStateFlow()

    private val _smart = MutableStateFlow(emptyList<SmartCollection>())
    val smart: StateFlow<List<SmartCollection>> = _smart.asStateFlow()

    private val _members = MutableStateFlow(
        emptyList<com.ssintelligence.app.domain.model.Screenshot>(),
    )
    val members: StateFlow<List<com.ssintelligence.app.domain.model.Screenshot>> =
        _members.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val repository = locator.screenshotRepository
            _collections.value = runCatching { repository.collections() }.getOrDefault(emptyList())
            _smart.value = runCatching { repository.smartGroups() }
                .getOrDefault(emptyList())
                .map { group ->
                    SmartCollection(
                        id = group.id,
                        label = group.label,
                        size = group.size,
                        memberIds = group.memberIds,
                        criteria = criteriaFor(group),
                    )
                }
        }
    }

    /**
     * The rule behind a smart collection, stated so inspection is possible.
     * Derived from how the group was keyed, not from a separate rules engine
     * that could drift out of sync with the builder.
     */
    private fun criteriaFor(group: com.ssintelligence.app.semantic.SmartGroup): List<String> {
        val (kind, value) = group.id.split(':', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else "group" to group.id
        }
        return when (kind) {
            "category" -> {
                val label = runCatching {
                    com.ssintelligence.app.semantic.ScreenshotCategory.valueOf(value).label
                }.getOrDefault(value)
                listOf("Automatic $label category")
            }

            "host" -> listOf("Screenshots from $value")
            else -> listOf("Screenshots sharing \"$value\"")
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            runCatching { locator.screenshotRepository.createCollection(name) }
            refresh()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { locator.screenshotRepository.deleteCollection(id) }
            refresh()
        }
    }

    fun loadMembers(collectionId: Long) {
        viewModelScope.launch {
            _members.value = runCatching {
                locator.screenshotRepository.collectionMembers(collectionId)
            }.getOrDefault(emptyList())
        }
    }

    fun loadSmartMembers(ids: List<Long>) {
        viewModelScope.launch {
            _members.value = ids.mapNotNull { locator.screenshotRepository.getById(it) }
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CollectionsViewModel(locator) as T
    }
}
