package com.ssintelligence.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.assistant.AssistantAction
import com.ssintelligence.app.assistant.AssistantActionRequest
import com.ssintelligence.app.assistant.AssistantResponse
import com.ssintelligence.app.ui.common.ScreenshotThumbnail
import com.ssintelligence.app.ui.common.DateFormats
import kotlinx.coroutines.launch
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Executes a confirmed assistant action proposal (§77).
 *
 * Each typed command maps to exactly one repository write or system intent.
 * The result landing in the action history is what the next answer reports —
 * so "I added the event" is only ever said after Android accepted the intent.
 */
private suspend fun executeProposal(
    locator: ServiceLocator,
    proposal: AssistantAction.ProposeAction,
    response: AssistantResponse,
) {
    val firstId = response.sources.firstOrNull()?.screenshotId ?: return
    val executor = com.ssintelligence.app.actions.ActionExecutor(locator.toApplicationContext())
    when (val command = proposal.command) {
        is AssistantActionRequest.CreateReminder -> {
            locator.actionRepository.createReminder(command.title, command.dueEpochMillis, firstId)
            locator.actionRepository.recordAction("CREATE_REMINDER", firstId, command.title, true)
        }

        is AssistantActionRequest.SaveExpense -> {
            locator.actionRepository.saveExpense(
                firstId, command.merchant, command.amount, command.currency,
                java.time.LocalDate.now().toEpochDay(), null,
            )
            locator.actionRepository.recordAction("SAVE_EXPENSE", firstId, "Saved expense", true)
        }

        is AssistantActionRequest.AddToCalendar -> {
            executor.execute(
                com.ssintelligence.app.actions.ContextAction(
                    id = "assistant-calendar",
                    type = com.ssintelligence.app.actions.ActionType.CREATE_CALENDAR_EVENT,
                    title = command.title,
                    description = null,
                    screenshotIds = listOf(firstId),
                    entityIds = emptyList(),
                    confirmation = com.ssintelligence.app.actions.ConfirmationLevel.CONFIRM,
                    permission = com.ssintelligence.app.actions.PermissionType.NONE,
                    sensitivity = com.ssintelligence.app.assistant.SensitivityLevel.NORMAL,
                    payload = com.ssintelligence.app.actions.ActionPayload.CalendarEvent(
                        command.title, command.startEpochMillis, null, null, null,
                    ),
                ),
            )
        }

        is AssistantActionRequest.CreateCollection -> {
            locator.screenshotRepository.createCollection(command.name)
            locator.actionRepository.recordAction("CREATE_COLLECTION", firstId, command.name, true)
        }

        is AssistantActionRequest.OpenUrl -> {
            executor.execute(
                com.ssintelligence.app.actions.ContextAction(
                    id = "assistant-url",
                    type = com.ssintelligence.app.actions.ActionType.OPEN_URL,
                    title = command.url,
                    description = null,
                    screenshotIds = listOf(firstId),
                    entityIds = emptyList(),
                    confirmation = com.ssintelligence.app.actions.ConfirmationLevel.NONE,
                    permission = com.ssintelligence.app.actions.PermissionType.NONE,
                    sensitivity = com.ssintelligence.app.assistant.SensitivityLevel.NORMAL,
                    payload = com.ssintelligence.app.actions.ActionPayload.Url(command.url),
                ),
            )
        }
    }
}

/**
 * The assistant screen (§39).
 *
 * A conversation over the local screenshot index. Every answer carries its
 * sources, its confidence type and its evidence chain; the user can inspect any
 * of them. Nothing here talks to a network: the pipeline is entirely local.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenScreenshot: (Long) -> Unit,
    /** A screenshot the user is asking about from its detail page (§14). */
    anchor: Long? = null,
    /** A multi-selection the user is asking about (§15). */
    selection: List<Long> = emptyList(),
    viewModel: AssistantViewModel = viewModel(factory = AssistantViewModel.Factory(locator)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // An anchored or selected question is asked immediately, so "Ask about this
    // screenshot" behaves like opening a chat that already knows the subject.
    LaunchedEffect(anchor, selection) {
        val question = when {
            anchor != null -> "What is this screenshot about?"
            selection.size == 1 -> "What is this screenshot about?"
            selection.size > 1 -> "What is different between these ${selection.size} screenshots?"
            else -> null
        }
        if (question != null && state.turns.isEmpty()) {
            input = question
            viewModel.ask(question)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screenshot Memory") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::newConversation) {
                        Icon(Icons.Filled.Bolt, contentDescription = "New conversation")
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
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.turns.isEmpty()) {
                    item("intro") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Ask anything about your screenshots",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Everything is answered from your own indexed screenshots, on this device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SsColors.TextSecondary,
                            )
                        }
                    }
                    items(state.suggestions, key = { it.text }) { suggestion ->
                        AssistChip(
                            onClick = {
                                input = suggestion.text
                                viewModel.ask(suggestion.text)
                            },
                            label = { Text(suggestion.text) },
                        )
                    }
                }
                items(state.turns, key = { it.userText + it.response?.answer?.length }) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = turn.userText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.End),
                        )
                        AnswerCard(
                            response = turn.response,
                            onOpenScreenshot = onOpenScreenshot,
                            onActionConfirmed = { proposal ->
                                turn.response?.let { response ->
                                    scope.launch {
                                        executeProposal(locator, proposal, response)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (state.thinking) {
                Text(
                    text = "Searching your screenshots…",
                    style = MaterialTheme.typography.bodySmall,
                    color = SsColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = SsColors.Error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Find that Pixel deal…") },
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        viewModel.ask(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !state.thinking,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(
    response: AssistantResponse?,
    onOpenScreenshot: (Long) -> Unit,
    onActionConfirmed: (AssistantAction.ProposeAction) -> Unit,
) {
    if (response == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Searching…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SsColors.SurfaceElevated,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = response.answer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = response.confidenceType.label,
                style = MaterialTheme.typography.labelSmall,
                color = SsColors.TextSecondary,
            )
            if (response.sources.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    response.sources.take(4).forEach { source ->
                        Surface(
                            onClick = { onOpenScreenshot(source.screenshotId) },
                            shape = MaterialTheme.shapes.small,
                            color = SsColors.SurfaceVariant,
                        ) {
                            Column(
                                Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = source.filename,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                                Text(
                                    text = DateFormats.formatDate(source.dateAdded),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SsColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
            if (response.relatedEntities.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    response.relatedEntities.forEach { entity ->
                        AssistChip(onClick = {}, label = { Text(entity) })
                    }
                }
            }
            if (response.requiresReveal) {
                Text(
                    text = "Sensitive content is masked. Reveal it from the screenshot.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SsColors.Error,
                )
            }
            // Phase 7 action cards (§76): the assistant proposes, the user
            // confirms, and only then does anything execute.
            val proposals = response.actions.filterIsInstance<AssistantAction.ProposeAction>()
            if (proposals.isNotEmpty()) {
                var confirmed by remember { mutableStateOf<AssistantAction.ProposeAction?>(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    proposals.forEach { proposal ->
                        AssistChip(
                            onClick = { confirmed = proposal },
                            label = { Text(proposal.label) },
                        )
                    }
                }
                confirmed?.let { proposal ->
                    AlertDialog(
                        onDismissRequest = { confirmed = null },
                        title = { Text(proposal.label) },
                        text = { Text("This acts on your screenshots. Nothing happens until you confirm.") },
                        confirmButton = {
                            TextButton(onClick = {
                                onActionConfirmed(proposal)
                                confirmed = null
                            }) { Text("Confirm") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmed = null }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}
