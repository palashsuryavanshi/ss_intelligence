package com.ssintelligence.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ssintelligence.app.search.ContentType
import com.ssintelligence.app.search.Currency
import com.ssintelligence.app.search.ManualFilters
import com.ssintelligence.app.search.TimeRange
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Manual refinements the parser cannot infer (§30).
 *
 * These narrow a natural-language query rather than replacing it: the sheet
 * never clears the text box, because a filter chip and a sentence are two ways
 * of saying the same thing and the user may be using both.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    current: ManualFilters,
    contentTypes: Set<ContentType>,
    onApply: (ManualFilters, Set<ContentType>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        FilterSheetContent(
            initial = current,
            initialTypes = contentTypes,
            onApply = onApply,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheetContent(
    initial: ManualFilters,
    initialTypes: Set<ContentType>,
    onApply: (ManualFilters, Set<ContentType>) -> Unit,
) {
    var minPrice by remember { mutableStateOf(initial.minPrice?.toPlainString() ?: "") }
    var maxPrice by remember { mutableStateOf(initial.maxPrice?.toPlainString() ?: "") }
    var currency by remember { mutableStateOf(initial.currency) }
    var hasOtp by remember { mutableStateOf(initial.hasOtp == true) }
    var duplicatesOnly by remember { mutableStateOf(initial.duplicatesOnly == true) }
    var types by remember { mutableStateOf(initialTypes) }
    var dateChoice by remember { mutableStateOf(initial.timeRange.toDateChoice()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Refine results",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )

        Text("Contains", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContentType.entries.forEach { type ->
                FilterChip(
                    selected = type in types,
                    onClick = { types = types.toggle(type) },
                    label = { Text(type.chipLabel()) },
                )
            }
        }

        HorizontalDivider()

        Text("Price range", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = minPrice,
                onValueChange = { minPrice = it.keepDigitsAndSeparators() },
                label = { Text("Minimum") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxPrice,
                onValueChange = { maxPrice = it.keepDigitsAndSeparators() },
                label = { Text("Maximum") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CURRENCY_CHOICES.forEach { code ->
                FilterChip(
                    selected = currency == code,
                    onClick = { currency = if (currency == code) null else code },
                    label = { Text(Currency.symbol(code) ?: code) },
                )
            }
        }

        HorizontalDivider()

        Text("Date", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateChoice.entries.forEach { choice ->
                FilterChip(
                    selected = dateChoice == choice,
                    onClick = { dateChoice = choice },
                    label = { Text(choice.label) },
                )
            }
        }

        HorizontalDivider()

        ToggleRow(
            label = "Only screenshots with a one-time code",
            checked = hasOtp,
            onChange = { hasOtp = it },
        )
        ToggleRow(
            label = "Only duplicate screenshots",
            checked = duplicatesOnly,
            onChange = { duplicatesOnly = it },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    minPrice = ""
                    maxPrice = ""
                    currency = null
                    hasOtp = false
                    duplicatesOnly = false
                    types = emptySet()
                    dateChoice = DateChoice.ANY
                },
                modifier = Modifier.weight(1f),
            ) { Text("Reset") }
            Button(
                onClick = {
                    onApply(
                        ManualFilters(
                            minPrice = minPrice.toAmount(),
                            maxPrice = maxPrice.toAmount(),
                            currency = currency,
                            timeRange = dateChoice.toTimeRange(),
                            hasOtp = hasOtp.takeIf { it },
                            duplicatesOnly = duplicatesOnly.takeIf { it },
                        ),
                        types,
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Apply") }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ------------------------------------------------------------------ helpers

private val CURRENCY_CHOICES = listOf(
    Currency.INR,
    Currency.USD,
    Currency.EUR,
    Currency.GBP,
)

/** Relative date choices, mirroring the words the parser understands (§30). */
private enum class DateChoice(val label: String) {
    ANY("Any time"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    LAST_WEEK("Last week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_YEAR("This year"),
}

private fun Set<ContentType>.toggle(type: ContentType): Set<ContentType> =
    if (type in this) this - type else this + type

private fun ContentType.chipLabel(): String = when (this) {
    ContentType.URLS -> "Links"
    ContentType.PRICES -> "Prices"
    ContentType.DATES -> "Dates"
    ContentType.PHONES -> "Phone numbers"
    ContentType.OTPS -> "Codes"
    ContentType.DUPLICATES -> "Duplicates"
}

private fun String.keepDigitsAndSeparators(): String =
    filter { it.isDigit() || it == ',' || it == '.' }.take(12)

private fun String.toAmount(): Double? =
    replace(",", "").toDoubleOrNull()?.takeIf { it >= 0 }

/** `40000.0` reads as `40000` in a text field. */
private fun Double.toPlainString(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun TimeRange?.toDateChoice(): DateChoice {
    val range = this ?: return DateChoice.ANY
    val today = LocalDate.now()
    val start = LocalDate.ofInstant(Instant.ofEpochMilli(range.startMillis), ZoneId.systemDefault())
    val end = LocalDate.ofInstant(Instant.ofEpochMilli(range.endMillis), ZoneId.systemDefault())
        .minusDays(1)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return when {
        start == today && end == today -> DateChoice.TODAY
        start == today.minusDays(1) && end == start -> DateChoice.YESTERDAY
        start == weekStart && end == start.plusDays(6) -> DateChoice.THIS_WEEK
        start == weekStart.minusWeeks(1) && end == start.plusDays(6) -> DateChoice.LAST_WEEK
        start == today.withDayOfMonth(1) -> DateChoice.THIS_MONTH
        start == today.minusMonths(1).withDayOfMonth(1) -> DateChoice.LAST_MONTH
        start == today.withDayOfYear(1) -> DateChoice.THIS_YEAR
        else -> DateChoice.ANY
    }
}

private fun DateChoice.toTimeRange(): TimeRange? {
    val today = LocalDate.now()
    return when (this) {
        DateChoice.ANY -> null
        DateChoice.TODAY -> TimeRange.day(today)
        DateChoice.YESTERDAY -> TimeRange.day(today.minusDays(1))
        DateChoice.THIS_WEEK -> {
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            TimeRange.days(start, start.plusDays(6))
        }

        DateChoice.LAST_WEEK -> {
            val start = today.minusWeeks(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            TimeRange.days(start, start.plusDays(6))
        }

        DateChoice.THIS_MONTH -> TimeRange.day(today.withDayOfMonth(1))
        DateChoice.LAST_MONTH -> TimeRange.day(today.minusMonths(1).withDayOfMonth(1))
        DateChoice.THIS_YEAR -> TimeRange.day(today.withDayOfYear(1))
    }
}
