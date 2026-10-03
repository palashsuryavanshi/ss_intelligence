package com.ssintelligence.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssintelligence.app.search.MatchKind
import com.ssintelligence.app.search.MatchReason
import com.ssintelligence.app.search.SearchResult
import com.ssintelligence.app.search.Snippet
import com.ssintelligence.app.ui.common.DateFormats
import com.ssintelligence.app.ui.common.ScreenshotThumbnail
import com.ssintelligence.app.ui.common.thumbnailModifier
import com.ssintelligence.app.ui.theme.SsColors

/**
 * One search result (§26, §27).
 *
 * Shows a thumbnail, the matched OCR snippet, the detected metadata that made
 * this row a candidate, and the date. It deliberately does **not** show a
 * relevance number: there is no probability model behind the score, so a
 * "92% match" badge would be a fabrication. What it shows instead is the list
 * of things that actually matched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SsColors.SurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenshotThumbnail(
                screenshot = result.screenshot,
                modifier = thumbnailModifier(),
                // The card is read as one unit; a separate focus stop on the
                // image would make a screen reader announce it twice.
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val snippet = result.snippet
                if (snippet != null) {
                    Text(
                        text = snippet.annotated(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = result.screenshot.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (result.matches.isNotEmpty()) {
                    MatchReasons(result.matches)
                }

                if (result.collapsedCount > 1) {
                    CollapsedNote(count = result.collapsedCount)
                }

                Text(
                    text = DateFormats.formatDate(result.screenshot.dateAdded),
                    style = MaterialTheme.typography.labelMedium,
                    color = SsColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * "Matched: Pixel 9a, ₹39,999" (§27).
 *
 * One-time codes appear as the words "One-time code" and never as the value,
 * matching how the detail screen gates them.
 */
@Composable
private fun MatchReasons(matches: List<MatchReason>) {
    val described = remember(matches) {
        "Matched: " + matches.joinToString(", ") { it.label }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = described },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        matches.take(MAX_VISIBLE_MATCHES).forEach { reason ->
            Surface(
                color = SsColors.SurfaceVariant,
                contentColor = SsColors.TextSecondary,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = reason.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}

/**
 * "3 identical screenshots" (§34).
 *
 * The group occupies one rank instead of three. Tapping opens the
 * representative; the hidden members stay reachable from the duplicates
 * screen, so nothing is buried.
 */
@Composable
private fun CollapsedNote(count: Int) {
    Text(
        text = "$count identical screenshots",
        style = MaterialTheme.typography.labelSmall,
        color = SsColors.TextSecondary,
    )
}

/**
 * "Searching for: Pixel 9a · ₹39,999" (§25).
 *
 * Uses the words a person would use, never the parser's internal vocabulary:
 * no `intent=FIND`, no `priceFilter=EXACT` (§25).
 */
@Composable
fun ParsedQueryBanner(
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SsColors.NavyPrimary,
        contentColor = SsColors.TextOnNavy,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Searching for", style = MaterialTheme.typography.labelSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Says out loud that a constraint was dropped (§29).
 *
 * A relaxed result set shown without this notice would misrepresent what was
 * searched for, so the banner is part of the contract, not decoration.
 */
@Composable
fun RelaxationBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SsColors.SurfaceVariant,
        contentColor = SsColors.TextSecondary,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Section label for the idle screen. */
@Composable
fun SearchSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = SsColors.NavyAccent,
        modifier = modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** One autocomplete or recent-search chip (§38). */
@Composable
fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
    )
}

/**
 * A metadata chip.
 *
 * Always labelled in text, never colour alone, so state is not communicated by
 * hue (§48).
 */
@Composable
fun InfoChip(
    label: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = if (emphasised) {
            SsColors.NavyPrimary
        } else {
            SsColors.SurfaceVariant
        },
        contentColor = if (emphasised) {
            SsColors.TextOnNavy
        } else {
            SsColors.TextSecondary
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Small coloured marker for a match kind, used by the debug screen (§46). */
@Composable
internal fun MatchKindDot(kind: MatchKind, modifier: Modifier = Modifier) {
    val color = when (kind) {
        MatchKind.PHRASE -> SsColors.NavyAccent
        MatchKind.PRICE -> SsColors.NavyBright
        MatchKind.URL, MatchKind.PHONE -> SsColors.TextSecondary
        MatchKind.OTP -> SsColors.Error
        MatchKind.SEMANTIC -> SsColors.NavyBright
        MatchKind.VISUAL, MatchKind.ENTITY -> SsColors.TextSecondary
        MatchKind.DATE, MatchKind.FILENAME, MatchKind.DUPLICATE -> SsColors.Outline
        MatchKind.TERM -> SsColors.Divider
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

/**
 * Renders a snippet with its matched ranges emphasised.
 *
 * Emphasis is weight, not colour, so it survives dark mode and is not the only
 * signal distinguishing a match (§48).
 */
internal fun Snippet.annotated(): AnnotatedString = buildAnnotatedString {
    append(text)
    highlights.forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) {
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, end)
        }
    }
}

/** Shared content padding for the search list. */
internal val SearchListPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

private const val MAX_VISIBLE_MATCHES = 4
