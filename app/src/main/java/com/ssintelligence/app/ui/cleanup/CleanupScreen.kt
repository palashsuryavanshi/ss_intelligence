package com.ssintelligence.app.ui.cleanup

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.ssintelligence.app.autonomous.SuggestionKind
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Cleanup center (§21): reviewable suggestions, never automatic deletion.
 *
 * Every category is a question the user can act on. Deletion always requires
 * explicit confirmation, and the autonomous system never deletes on its own (§64).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: CleanupViewModel = viewModel(factory = CleanupViewModel.Factory(locator)),
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screenshot Cleanup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (suggestions.isEmpty()) {
            EmptyState(
                title = "Nothing to review",
                message = "Suggestions appear here as your library is analyzed. " +
                    "Nothing is ever deleted without your confirmation.",
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
            item("header") {
                SectionHeader("Review suggestions")
            }
            item("note") {
                Text(
                    text = "These are suggestions only. Nothing is deleted or changed " +
                        "without your explicit action.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(suggestions, key = { it.id }) { suggestion ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = suggestion.kind.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = suggestion.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { onOpenScreenshot(suggestion.screenshotIds.first()) }) {
                                Text("Review")
                            }
                            OutlinedButton(onClick = { viewModel.dismiss(suggestion.id) }) {
                                Text("Ignore")
                            }
                        }
                    }
                }
            }
        }
    }
}

class CleanupViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    private val _suggestions = MutableStateFlow(emptyList<com.ssintelligence.app.autonomous.Suggestion>())
    val suggestions: StateFlow<List<com.ssintelligence.app.autonomous.Suggestion>> = _suggestions.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val repository = locator.autonomousRepository
            val out = mutableListOf<com.ssintelligence.app.autonomous.Suggestion>()
            // Near-duplicate groups become reviewable suggestions.
            val nearDupes = runCatching { locator.screenshotRepository.nearDuplicateGroups(50) }
                .getOrDefault(emptyList())
            for (group in nearDupes) {
                out += com.ssintelligence.app.autonomous.Suggestion(
                    id = group.coverId,
                    kind = SuggestionKind.NEAR_DUPLICATE,
                    screenshotIds = group.memberIds,
                    reason = "${group.size} screenshots look nearly identical.",
                )
            }
            // Information duplicates: screenshots with heavy OCR overlap.
            val sequences = runCatching { locator.screenshotRepository.sequences() }.getOrDefault(emptyList())
            for (sequence in sequences) {
                out += com.ssintelligence.app.autonomous.Suggestion(
                    id = sequence.memberIds.first(),
                    kind = SuggestionKind.REPEATED_INFORMATION,
                    screenshotIds = sequence.memberIds,
                    reason = "These screenshots contain very similar information.",
                )
            }
            _suggestions.value = out
        }
    }

    fun dismiss(id: Long) {
        viewModelScope.launch {
            // Dismissal is recorded so the suggestion stops recurring; the
            // screenshots themselves are untouched.
            locator.autonomousRepository.dismissSuggestion(id)
            refresh()
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CleanupViewModel(locator) as T
    }
}
