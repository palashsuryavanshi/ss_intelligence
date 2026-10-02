package com.ssintelligence.app.ui.insights

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
import com.ssintelligence.app.autonomous.EventConfidence
import com.ssintelligence.app.autonomous.EventType
import com.ssintelligence.app.autonomous.InsightKind
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Insights center (§51): every automatically derived observation, reviewable.
 *
 * Each insight names what was detected and links to the screenshots that produced
 * it. Nothing here is applied silently — the user reviews and decides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: InsightsViewModel = viewModel(factory = InsightsViewModel.Factory(locator)),
) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (insights.isEmpty()) {
            EmptyState(
                title = "No insights yet",
                message = "Insights appear once your screenshots have been analyzed.",
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
            item("header") { SectionHeader("Detected in your library") }
            items(insights, key = { it.id }) { insight ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = insight.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = insight.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

class InsightsViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    data class Insight(
        val id: String,
        val title: String,
        val detail: String,
        val kind: InsightKind,
    )

    private val _insights = MutableStateFlow(emptyList<Insight>())
    val insights: StateFlow<List<Insight>> = _insights.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val repository = locator.autonomousRepository
            val out = mutableListOf<Insight>()
            val topics = runCatching { repository.topics() }.getOrDefault(emptyList())
            for (topic in topics.take(10)) {
                out += Insight(
                    id = "topic-${topic.id}",
                    title = topic.label,
                    detail = "${topic.screenshotIds.size} screenshots · ${topic.signals.joinToString()}",
                    kind = InsightKind.TOPIC,
                )
            }
            val sessions = runCatching { repository.sessions() }.getOrDefault(emptyList())
            for (session in sessions.take(10)) {
                out += Insight(
                    id = "session-${session.id}",
                    title = session.label,
                    detail = "${session.screenshotIds.size} screenshots · ${session.signals.joinToString()}",
                    kind = InsightKind.SESSION,
                )
            }
            val events = runCatching { repository.events() }.getOrDefault(emptyList())
            for (event in events.take(10)) {
                out += Insight(
                    id = "event-${event.id}",
                    title = "${event.type.label} — ${event.confidence.label}",
                    detail = "${event.screenshotIds.size} screenshots · ${event.signals.joinToString()}",
                    kind = InsightKind.EVENT,
                )
            }
            _insights.value = out
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InsightsViewModel(locator) as T
    }
}
