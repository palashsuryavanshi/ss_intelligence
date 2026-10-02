package com.ssintelligence.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssintelligence.app.domain.model.Screenshot

/**
 * A single screenshot in a list (§23): thumbnail, filename, date and a short
 * OCR excerpt. The row is one semantics node so TalkBack reads it as a
 * sentence rather than five fragments (§37).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScreenshotRow(
    screenshot: Screenshot,
    onClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Selection state for the browse screen's "ask about these" mode (§15). */
    selected: Boolean = false,
    onLongClick: ((Long) -> Unit)? = null,
) {
    val preview = screenshot.ocrPreview()
    val spokenSummary = buildString {
        append("Screenshot ${screenshot.filename}, taken ${DateFormats.formatDate(screenshot.dateAdded)}")
        append(", ${screenshot.status.name.lowercase().replaceFirstChar { it.uppercase() }}")
        if (preview != null) append(". Contains text: $preview")
        if (screenshot.isDuplicate) append(". Exact duplicate of another screenshot")
    }

    Card(
        onClick = { onClick(screenshot.id) },
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = spokenSummary }
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = { onClick(screenshot.id) },
                        onLongClick = { onLongClick(screenshot.id) },
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenshotThumbnail(
                screenshot = screenshot,
                modifier = thumbnailModifier(),
                contentDescription = null, // described by the row instead
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = screenshot.filename,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = DateFormats.formatDateTime(screenshot.dateAdded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = preview ?: "No text found yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (preview == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(screenshot.status)
                    if (screenshot.isDuplicate) {
                        Text(
                            text = "Duplicate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
