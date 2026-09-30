package com.ssintelligence.app.ui.duplicates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.DuplicateGroup
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ScreenshotRow
import java.util.Locale

/**
 * Duplicate groups (§18, §22).
 *
 * Only exact, byte-level duplicates are detected in Phase 1; near-duplicate
 * detection is reserved for a future perceptual-hash implementation (§19).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    locator: ServiceLocator,
    onOpenScreenshot: (Long) -> Unit,
    viewModel: DuplicatesViewModel = viewModel(factory = DuplicatesViewModel.Factory(locator)),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Duplicates") }) },
    ) { padding ->
        if (groups.isEmpty()) {
            EmptyState(
                title = "No duplicate screenshots",
                message = "Groups appear here when the same image is saved more than once.",
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
            item("explanation") {
                Text(
                    text = "Identical image content, detected by SHA-256. Only the earliest " +
                        "copy in each set was read again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(groups, key = { it.contentHash }) { group ->
                DuplicateGroupCard(group = group, onOpenScreenshot = onOpenScreenshot)
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    onOpenScreenshot: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "${group.screenshots.size} copies",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "content hash ${group.contentHash.take(12)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            group.screenshots.forEach { screenshot ->
                ScreenshotRow(
                    screenshot = screenshot,
                    onClick = onOpenScreenshot,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

/** Unused placeholder kept out of the UI: formatting lives in DateFormats. */
@Suppress("unused")
private fun formatCount(value: Int): String =
    String.format(Locale.getDefault(), "%,d", value)
