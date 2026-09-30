package com.ssintelligence.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.ExtractedDate
import com.ssintelligence.app.domain.model.ExtractedPhone
import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ScreenshotDetail
import com.ssintelligence.app.ui.common.DateFormats
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.ErrorBanner
import com.ssintelligence.app.ui.common.MetadataRow
import com.ssintelligence.app.ui.common.OtpRevealRow
import com.ssintelligence.app.ui.common.ScreenshotImage
import com.ssintelligence.app.ui.common.SectionHeader
import com.ssintelligence.app.ui.common.StatusChip
import com.ssintelligence.app.ui.common.errorMessage
import com.ssintelligence.app.ui.theme.OcrTextStyle

/**
 * Screenshot detail (§24): the image, the OCR text, and the structured
 * information extracted from it.
 *
 * OTP values are masked until explicitly revealed (§17) and no extracted
 * value is ever placed in a share intent or notification (§40).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotDetailScreen(
    locator: ServiceLocator,
    screenshotId: Long,
    onBack: () -> Unit,
    viewModel: ScreenshotDetailViewModel = viewModel(
        factory = ScreenshotDetailViewModel.Factory(locator, screenshotId)
    ),
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detail?.screenshot?.filename ?: "Screenshot",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val error = detail?.screenshot?.errorMessage()
                    if (error != null) {
                        IconButton(onClick = viewModel::onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Retry indexing this screenshot")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = detail
        if (current == null) {
            EmptyState(
                title = "Screenshot not available",
                message = "It may have been removed from this device, or the index was cleared.",
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
            item("image") {
                ScreenshotImage(
                    uri = current.screenshot.uri,
                    contentDescription = "Full screenshot: ${current.screenshot.filename}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                )
            }

            item("status") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(current.screenshot.status)
                    current.screenshot.errorMessage()?.let { ErrorBanner(it) }
                    if (current.screenshot.isDuplicate) {
                        Text(
                            text = "Byte-identical to an earlier screenshot. Its extracted " +
                                "information is reused instead of re-reading the image.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("detected") {
                SectionHeader("Detected information")
            }

            if (current.urls.isNotEmpty()) {
                item("urls") {
                    InfoCard(title = "Links") {
                        current.urls.forEach { url ->
                            BulletRow(url.url, subtitle = url.host)
                        }
                    }
                }
            }
            if (current.prices.isNotEmpty()) {
                item("prices") {
                    InfoCard(title = "Prices") {
                        current.prices.forEach { row -> PriceRow(row) }
                    }
                }
            }
            if (current.dates.isNotEmpty()) {
                item("dates") {
                    InfoCard(title = "Dates") {
                        current.dates.forEach { date -> DateRow(date) }
                    }
                }
            }
            if (current.phones.isNotEmpty()) {
                item("phones") {
                    InfoCard(title = "Phone numbers") {
                        current.phones.forEach { phone -> PhoneRow(phone) }
                    }
                }
            }
            if (current.otps.isNotEmpty()) {
                item("otps") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeader("One-time codes")
                        OtpRevealRow(codes = current.otps.map { it.code })
                        Text(
                            text = "Hidden by default. These are sensitive and are never shown " +
                                "in lists or notifications.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (current.urls.isEmpty() && current.prices.isEmpty() && current.dates.isEmpty() &&
                current.phones.isEmpty() && current.otps.isEmpty()
            ) {
                item("no-extracted") {
                    Text(
                        text = "Nothing structured was detected in this screenshot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item("ocr") {
                SectionHeader("Text read from the image")
                if (current.screenshot.ocrText.isBlank()) {
                    Text(
                        text = "No text was recognised.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // The outer LazyColumn already scrolls, so the OCR text is
                    // a plain capped block rather than a nested scroll view —
                    // nesting two scrollables breaks measurement.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Text(
                            text = current.screenshot.ocrText,
                            style = OcrTextStyle,
                            maxLines = OCR_MAX_VISIBLE_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
            }

            item("metadata") {
                SectionHeader("Details")
                MetadataRow("Filename", current.screenshot.filename)
                MetadataRow(
                    "Dimensions",
                    "${current.screenshot.width} x ${current.screenshot.height}",
                )
                MetadataRow(
                    "Size",
                    DateFormats.formatFileSize(LocalContext.current, current.screenshot.fileSize),
                )
                current.screenshot.relativePath?.let {
                    MetadataRow("Folder", it)
                }
                MetadataRow("Added", DateFormats.formatDateTime(current.screenshot.dateAdded))
                MetadataRow("Modified", DateFormats.formatDateTime(current.screenshot.dateModified))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun BulletRow(value: String, subtitle: String? = null) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(text = "• $value", style = MaterialTheme.typography.bodyMedium)
        if (subtitle != null) {
            Text(
                text = "  $subtitle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PriceRow(price: ExtractedPrice) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "• ${price.rawText}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${price.currency} ${price.amount}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DateRow(date: ExtractedDate) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "• ${date.rawText}", style = MaterialTheme.typography.bodyMedium)
        Text(
            // Dates without a year are shown with their raw text only, so the
            // UI never asserts a year the OCR did not read (§16).
            text = if (date.hasYear) DateFormats.formatEpochDay(date.epochDay) else "Year not stated",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val OCR_MAX_VISIBLE_LINES = 40

@Composable
private fun PhoneRow(phone: ExtractedPhone) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "• ${phone.rawText}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = phone.normalized,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
