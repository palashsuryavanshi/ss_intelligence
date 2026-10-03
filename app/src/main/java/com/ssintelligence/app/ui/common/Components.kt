package com.ssintelligence.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ssintelligence.app.domain.model.ProcessingStatus
import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Thumbnail for a screenshot row (§23).
 *
 * Only a downscaled decode is requested, so a list of full-resolution
 * screenshots never inflates memory (§31). Coil owns the disk/memory cache,
 * which satisfies the "thumbnails must be cached" requirement.
 */
@Composable
fun ScreenshotThumbnail(
    screenshot: Screenshot,
    modifier: Modifier = Modifier,
    contentDescription: String? = "Screenshot: ${screenshot.filename}",
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SsColors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(screenshot.uri)
                .size(144)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Full-size preview for the detail screen (§24). */
@Composable
fun ScreenshotImage(
    uri: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SsColors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Status indicator. The label is always spelled out so state is never
 * communicated by colour alone (§37).
 */
@Composable
fun StatusChip(status: ProcessingStatus, modifier: Modifier = Modifier) {
    val (label, container, content) = when (status) {
        ProcessingStatus.COMPLETED -> Triple(
            "Indexed",
            SsColors.SurfaceVariant,
            SsColors.TextSecondary,
        )

        ProcessingStatus.PENDING -> Triple(
            "Queued",
            SsColors.SurfaceVariant,
            SsColors.TextSecondary,
        )

        ProcessingStatus.PROCESSING -> Triple(
            "Reading text",
            SsColors.NavyPrimary,
            SsColors.TextOnNavy,
        )

        ProcessingStatus.FAILED -> Triple(
            "Not indexed",
            SsColors.ErrorContainer,
            SsColors.TextPrimary,
        )
    }
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Human-readable processing error, or null when the row is healthy. */
fun Screenshot.errorMessage(): String? = when (status) {
    ProcessingStatus.FAILED -> error ?: "This screenshot could not be read."
    else -> null
}

/** One-line OCR excerpt, whitespace-collapsed and clipped (§23). */
private val WHITESPACE_REGEX = Regex("\\s+")

fun Screenshot.ocrPreview(maxLength: Int = 140): String? {
    val collapsed = ocrText.replace(WHITESPACE_REGEX, " ").trim()
    if (collapsed.isEmpty()) return null
    return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength).trimEnd() + "…"
}

/** Indexing progress banner shown on Home while work is in flight (§22). */
@Composable
fun IndexingProgressCard(
    processed: Int,
    total: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (total > 0) processed.toFloat() / total else 0f
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SsColors.NavyPrimary,
        contentColor = SsColors.TextOnNavy,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Indexing screenshots…", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "You can keep using the app. Everything runs on this device.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .semantics {
                        contentDescription =
                            "Indexing progress: $processed of $total screenshots processed"
                    },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$processed / $total",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.TextButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}

/** Friendly empty state (§39). */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = SsColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Box(Modifier.padding(top = 8.dp)) { action() }
        }
    }
}

/** Recoverable error surface (§30). */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SsColors.ErrorContainer,
        contentColor = SsColors.TextPrimary,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onRetry != null) {
                androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/**
 * OTP value with reveal-on-demand gating (§17, §24, §40).
 *
 * OTPs are never rendered in previews, list rows or notifications, and the
 * reveal state is local to this composable so it resets when the detail screen
 * is left.
 */
@Composable
fun OtpRevealRow(
    codes: List<String>,
    modifier: Modifier = Modifier,
) {
    if (codes.isEmpty()) return
    var revealed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SsColors.SurfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (revealed) codes.joinToString(", ") else "•".repeat(codes.sumOf { it.length }),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = if (revealed) "Hide one-time codes" else "Reveal one-time codes",
                )
            }
        }
    }
}

/** Section header used by the detail screen (§24). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = SsColors.NavyAccent,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Label/value row for metadata (§24). */
@Composable
fun MetadataRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = SsColors.TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Centered spinner for first loads. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Loading" },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** List content padding shared by the browse and search screens. */
val ListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/** Fixed thumbnail size for list rows, keeping rows visually consistent. */
fun thumbnailModifier(): Modifier = Modifier
    .size(72.dp)
    .aspectRatio(9f / 16f)
    .clearAndSetSemantics { }
