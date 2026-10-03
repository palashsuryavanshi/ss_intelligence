package com.ssintelligence.app.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.repository.EventGroup
import com.ssintelligence.app.domain.repository.SequenceGroup
import com.ssintelligence.app.domain.repository.TimelineDay
import com.ssintelligence.app.ui.common.DateFormats
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Timeline intelligence (§22–§24).
 *
 * Days the user actually took screenshots, each with that day's screenshots
 * and its top categories. Below the days: event groups (one booking across
 * days is one trip) and sequences (adjacent overlapping shots are one flow).
 * Dates are never fabricated — a day appears only when screenshots exist on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: TimelineViewModel = viewModel(factory = TimelineViewModel.Factory(locator)),
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val sequences by viewModel.sequences.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timeline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (days.isEmpty() && events.isEmpty() && sequences.isEmpty()) {
            EmptyState(
                title = "No timeline yet",
                message = "Screenshot days, events and sequences appear here once the " +
                    "library is indexed.",
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
            if (events.isNotEmpty()) {
                item("events-header") { SectionHeader("Events") }
                items(events, key = { "event-${it.label}" }) { event ->
                    EventCard(event = event, onOpenScreenshot = onOpenScreenshot)
                }
            }
            if (sequences.isNotEmpty()) {
                item("seq-header") { SectionHeader("Sequences") }
                items(sequences, key = { "seq-${it.label}" }) { sequence ->
                    EventCard(
                        event = EventGroup(
                            label = sequence.label,
                            memberIds = sequence.memberIds,
                            startSeconds = 0,
                            endSeconds = 0,
                        ),
                        onOpenScreenshot = onOpenScreenshot,
                    )
                }
            }
            days.forEach { day ->
                item("day-${day.epochDay}") {
                    Text(
                        text = dayLabel(day.epochDay),
                        style = MaterialTheme.typography.titleSmall,
                        color = SsColors.NavyAccent,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { heading() },
                    )
                    if (day.categories.isNotEmpty()) {
                        Text(
                            text = day.categories.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = SsColors.TextSecondary,
                        )
                    }
                }
                items(day.screenshots, key = { it.id }) { screenshot ->
                    ScreenshotRow(screenshot = screenshot, onClick = { onOpenScreenshot(screenshot.id) })
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    event: EventGroup,
    onOpenScreenshot: (Long) -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = SsColors.SurfaceElevated,
        ),
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = event.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${event.memberIds.size} screenshot${if (event.memberIds.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = SsColors.TextSecondary,
            )
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                event.memberIds.take(3).forEach { id ->
                    androidx.compose.material3.TextButton(onClick = { onOpenScreenshot(id) }) {
                        Text("Open")
                    }
                }
            }
        }
    }
}

private fun dayLabel(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateFormats.formatEpochDay(epochDay)
    }
}

class TimelineViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    private val _days = MutableStateFlow(emptyList<TimelineDay>())
    val days: StateFlow<List<TimelineDay>> = _days.asStateFlow()

    private val _events = MutableStateFlow(emptyList<EventGroup>())
    val events: StateFlow<List<EventGroup>> = _events.asStateFlow()

    private val _sequences = MutableStateFlow(emptyList<SequenceGroup>())
    val sequences: StateFlow<List<SequenceGroup>> = _sequences.asStateFlow()

    init {
        viewModelScope.launch {
            val repository = locator.screenshotRepository
            _days.value = runCatching { repository.timeline() }.getOrDefault(emptyList())
            _events.value = runCatching { repository.eventGroups() }.getOrDefault(emptyList())
            _sequences.value = runCatching { repository.sequences() }.getOrDefault(emptyList())
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimelineViewModel(locator) as T
    }
}
