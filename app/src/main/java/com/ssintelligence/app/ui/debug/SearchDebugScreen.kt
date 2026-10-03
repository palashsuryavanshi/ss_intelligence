package com.ssintelligence.app.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.search.SearchResponse
import com.ssintelligence.app.ui.common.DateFormats
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Search inspector — **debug builds only** (§46).
 *
 * Shows the raw query, everything the parser understood, how many candidates
 * SQL returned, and each result's score with the reasons behind it. This is
 * the screen that turns "why did that come first?" into a question with an
 * answer.
 *
 * It exposes internal vocabulary on purpose, which is exactly why it is not
 * part of the release surface: the navigation route is registered only when
 * `BuildConfig.DEBUG` is true, so a release build has no way to reach it and no
 * code path that renders it. It is never linked from the user-facing search
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchDebugScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    viewModel: SearchDebugViewModel = viewModel(factory = SearchDebugViewModel.Factory(locator)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search inspector") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Query") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::run, modifier = Modifier.fillMaxWidth()) {
                Text("Parse and search")
            }

            val parsed = state.parsed
            if (parsed != null) {
                HorizontalDivider()
                Labelled("Original query") { Text(parsed.originalQuery) }
                Labelled("Intent") { Text(parsed.intent.name) }
                Labelled("Terms") { Text(parsed.textTerms.joinToString().ifEmpty { "—" }) }
                Labelled("Phrases") { Text(parsed.phrases.joinToString().ifEmpty { "—" }) }
                Labelled("FTS terms") { Text(parsed.ftsTerms.joinToString().ifEmpty { "—" }) }
                Labelled("Prices") {
                    Text(parsed.prices.joinToString { it.display() }.ifEmpty { "—" })
                }
                Labelled("Dates") {
                    Text(
                        parsed.dateFilters.joinToString { "${it.label} (${it.startEpochDay}…${it.endEpochDayInclusive})" }
                            .ifEmpty { "—" },
                    )
                }
                Labelled("Domains") { Text(parsed.urls.joinToString().ifEmpty { "—" }) }
                Labelled("Phones") { Text(parsed.phoneNumbers.joinToString().ifEmpty { "—" }) }
                Labelled("Codes") {
                    // Presence only. The value is a secret even in a debug tool.
                    Text(if (parsed.otpCodes.isEmpty()) "—" else "${parsed.otpCodes.size} matched")
                }
                Labelled("Content types") {
                    Text(parsed.contentTypes.joinToString { it.name }.ifEmpty { "—" })
                }
                Labelled("Described as") { Text(parsed.describe()) }
            }

            val response = state.response
            if (response != null) {
                HorizontalDivider()
                Summary(response)
            }

            state.error?.let { message ->
                HorizontalDivider()
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(response: SearchResponse) {
    Labelled("Relaxation") { Text(response.relaxation.name) }
    Labelled("Candidates") { Text(response.candidateCount.toString()) }
    Labelled("Results") { Text(response.results.size.toString()) }
    Labelled("Elapsed") { Text("${response.elapsedMillis} ms") }

    HorizontalDivider()
    Text("Ranked results", style = MaterialTheme.typography.titleSmall)

    response.results.take(MAX_ROWS).forEach { result ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "#${result.score}  ${result.screenshot.filename}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateFormats.formatDateTime(result.screenshot.dateAdded),
                style = MaterialTheme.typography.labelSmall,
                color = SsColors.TextSecondary,
            )
            if (result.matches.isEmpty()) {
                Text(
                    text = "no explicit match reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = SsColors.TextSecondary,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.matches.forEach { reason ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            com.ssintelligence.app.ui.search.MatchKindDot(reason.kind)
                            Text(
                                text = "${reason.kind}: ${reason.label}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            result.snippet?.let { snippet ->
                Text(
                    text = snippet.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = SsColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (response.results.size > MAX_ROWS) {
        Text(
            text = "… and ${response.results.size - MAX_ROWS} more",
            style = MaterialTheme.typography.labelSmall,
            color = SsColors.TextSecondary,
        )
    }
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = SsColors.TextSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/** Keeps the summary honest about how much of the result set is displayed. */
private const val MAX_ROWS = 20
