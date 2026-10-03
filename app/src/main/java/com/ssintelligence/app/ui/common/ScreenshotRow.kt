package com.ssintelligence.app.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.ui.theme.SsColors

/**
 * A single screenshot in a list (§23): thumbnail, filename, date and a short
 * OCR excerpt. The row is one semantics node so TalkBack reads it as a
 * sentence rather than five fragments (§37).
 *
 * Derived strings are computed once per screenshot id, not on every
 * recomposition, so fast scrolling never re-parses OCR text.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ScreenshotRow(
    screenshot: Screenshot,
    onClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Selection state for the browse screen's "ask about these" mode (§15). */
    selected: Boolean = false,
    onLongClick: ((Long) -> Unit)? = null,
    /** When provided, the thumbnail animates into the detail hero image. */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    // Computed once per item: scrolling reuses compositions without redoing
    // string work.
    val preview = remember(screenshot.id, screenshot.ocrText) {
        screenshot.ocrPreview()
    }
    val dateAdded = remember(screenshot.id, screenshot.dateAdded) {
        DateFormats.formatDateTime(screenshot.dateAdded)
    }
    val statusLabel = remember(screenshot.id, screenshot.status) {
        screenshot.status.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    val spokenSummary = remember(screenshot.id, screenshot.filename, preview, statusLabel) {
        buildString {
            append("Screenshot ${screenshot.filename}, taken ${DateFormats.formatDate(screenshot.dateAdded)}")
            append(", $statusLabel")
            if (preview != null) append(". Contains text: $preview")
            if (screenshot.isDuplicate) append(". Exact duplicate of another screenshot")
        }
    }

    // A row is either tap-only (Card handles the click) or tap-and-hold (the
    // combinedClickable handles both). Never both: two click handlers would
    // fire navigation twice and double the input work per tap.
    if (onLongClick != null) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = spokenSummary }
                .combinedClickable(
                    onClick = { onClick(screenshot.id) },
                    onLongClick = { onLongClick(screenshot.id) },
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) {
                    SsColors.NavyPrimary
                } else {
                    SsColors.Surface
                },
            ),
        ) {
            ScreenshotRowContent(
                screenshot = screenshot,
                preview = preview,
                dateAdded = dateAdded,
                statusLabel = statusLabel,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Card(
            onClick = { onClick(screenshot.id) },
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = spokenSummary },
            colors = CardDefaults.cardColors(
                containerColor = if (selected) {
                    SsColors.NavyPrimary
                } else {
                    SsColors.Surface
                },
            ),
        ) {
            ScreenshotRowContent(
                screenshot = screenshot,
                preview = preview,
                dateAdded = dateAdded,
                statusLabel = statusLabel,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }
}

@Composable
private fun ScreenshotRowContent(
    screenshot: Screenshot,
    preview: String?,
    dateAdded: String,
    statusLabel: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
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
                text = dateAdded,
                style = MaterialTheme.typography.bodySmall,
                color = SsColors.TextSecondary,
            )
            Text(
                text = preview ?: "No text found yet",
                style = MaterialTheme.typography.bodySmall,
                color = if (preview == null) {
                    SsColors.TextSecondary
                } else {
                    SsColors.TextPrimary
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
                        color = SsColors.TextSecondary,
                    )
                }
            }
        }
    }
}
