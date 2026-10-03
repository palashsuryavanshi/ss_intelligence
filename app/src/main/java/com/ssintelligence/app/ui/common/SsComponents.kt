package com.ssintelligence.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Reusable UI components for the AMOLED + Navy design system.
 *
 * These components ensure visual consistency across every screen.
 */

/** Spacing scale */
object SsSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** Corner radius scale */
object SsShapes {
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(10.dp)
    val lg = RoundedCornerShape(14.dp)
    val xl = RoundedCornerShape(20.dp)
}

/**
 * Section header with optional action.
 */
@Composable
fun SsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SsSpacing.md, vertical = SsSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = SsColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        action?.invoke()
    }
}

/**
 * Setting row with title, explanation, and control.
 *
 * Preserves all existing explanation text at full detail.
 */
@Composable
fun SsSettingRow(
    title: String,
    explanation: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    control: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SsSpacing.md, vertical = SsSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) SsColors.TextPrimary else SsColors.TextDisabled,
                )
            }
            control()
        }
        if (explanation.isNotEmpty()) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) SsColors.TextSecondary else SsColors.TextDisabled,
                modifier = Modifier.padding(top = SsSpacing.xs),
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4,
            )
        }
    }
}

/**
 * Compact info block for statistics.
 */
@Composable
fun SsInfoBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(SsShapes.md)
            .background(SsColors.Surface)
            .padding(SsSpacing.md),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = SsColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SsColors.TextSecondary,
            modifier = Modifier.padding(top = SsSpacing.xs),
        )
    }
}

/**
 * Status indicator dot.
 */
@Composable
fun SsStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}

/**
 * Polished empty state.
 */
@Composable
fun SsEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(SsSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = SsColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = SsColors.TextSecondary,
            modifier = Modifier.padding(top = SsSpacing.sm),
        )
        action?.let {
            Box(modifier = Modifier.padding(top = SsSpacing.lg)) {
                it()
            }
        }
    }
}

/**
 * Polished error state.
 */
@Composable
fun SsErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(SsSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = SsColors.Error,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = SsColors.TextSecondary,
            modifier = Modifier.padding(top = SsSpacing.sm),
        )
        action?.let {
            Box(modifier = Modifier.padding(top = SsSpacing.lg)) {
                it()
            }
        }
    }
}

/**
 * Metadata row: label on left, value on right.
 */
@Composable
fun SsMetadataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SsSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SsColors.TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = SsColors.TextPrimary,
        )
    }
}