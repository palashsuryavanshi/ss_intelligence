package com.ssintelligence.app.ui.actions

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
import com.ssintelligence.app.actions.ActionHistoryEntry
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Action center (§73): suggested and recent actions in one place.
 *
 * Suggested actions are generated from detected content; recent actions are
 * the recorded history of what was actually executed. Both link back to
 * their source screenshots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionCenterScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: ActionCenterViewModel = viewModel(factory = ActionCenterViewModel.Factory(locator)),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Actions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            EmptyState(
                title = "No actions yet",
                message = "Actions you take from screenshots are recorded here, " +
                    "with their results.",
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
            item("header") { SectionHeader("Recent") }
            items(history, key = { "history-${it.id}" }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = SsColors.SurfaceElevated,
                    ),
                    onClick = { onOpenScreenshot(entry.screenshotId) },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = entry.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (entry.success) "Completed" else "Did not complete",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.success) {
                                SsColors.TextSecondary
                            } else {
                                SsColors.Error
                            },
                        )
                    }
                }
            }
        }
    }
}

class ActionCenterViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    private val _history = MutableStateFlow(emptyList<ActionHistoryEntry>())
    val history: StateFlow<List<ActionHistoryEntry>> = _history.asStateFlow()

    init {
        viewModelScope.launch {
            locator.actionRepository.observeHistory(50).collect { _history.value = it }
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ActionCenterViewModel(locator) as T
    }
}
