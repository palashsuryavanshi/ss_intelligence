package com.ssintelligence.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.actions.LocalTask
import com.ssintelligence.app.actions.TaskState
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Local tasks view (§48): reminders, suggested actions and review items.
 *
 * Reminders are stored on this device and reference their source screenshots.
 * Suggested tasks are derived from detected content; nothing is scheduled
 * without the user. States are words — Suggested, Scheduled, Completed,
 * Dismissed — never hidden flags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory(locator)),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyState(
                title = "No tasks",
                message = "Reminders you create and actions the app suggests appear here. " +
                    "Nothing is scheduled without you.",
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
            val grouped = tasks.groupBy { it.state }
            for (state in TaskState.entries) {
                val group = grouped[state].orEmpty()
                if (group.isEmpty()) continue
                item("header-${state.name}") { SectionHeader(state.label) }
                items(group, key = { "task-${it.id}" }) { task ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        onClick = {
                            task.screenshotIds.firstOrNull()?.let(onOpenScreenshot)
                        },
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = task.title, style = MaterialTheme.typography.titleSmall)
                            if (task.detail.isNotBlank()) {
                                Text(
                                    text = task.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = task.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

class TasksViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    private val _tasks = MutableStateFlow(emptyList<LocalTask>())
    val tasks: StateFlow<List<LocalTask>> = _tasks.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val out = mutableListOf<LocalTask>()
            // Stored reminders become scheduled tasks.
            val reminders = runCatching {
                locator.actionRepository.observeReminders().first()
            }.getOrDefault(emptyList())
            for (reminder in reminders) {
                out += LocalTask(
                    id = "reminder-${reminder.id}",
                    title = reminder.title,
                    detail = "Reminder from a screenshot",
                    dueEpochMillis = reminder.dueEpochMillis,
                    screenshotIds = listOf(reminder.sourceScreenshotId),
                    state = TaskState.SCHEDULED,
                    source = "Reminder",
                )
            }
            // Suggestions become suggested tasks.
            val suggestions = runCatching {
                locator.autonomousRepository.observeSuggestions().first()
            }.getOrDefault(emptyList())
            for (suggestion in suggestions.take(20)) {
                out += LocalTask(
                    id = "suggestion-${suggestion.id}",
                    title = suggestion.kind.label,
                    detail = suggestion.reason,
                    dueEpochMillis = null,
                    screenshotIds = suggestion.screenshotIds,
                    state = TaskState.SUGGESTED,
                    source = "Suggested",
                )
            }
            _tasks.value = out
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TasksViewModel(locator) as T
    }
}
