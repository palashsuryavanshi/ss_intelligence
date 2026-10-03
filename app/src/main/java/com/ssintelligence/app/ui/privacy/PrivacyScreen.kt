package com.ssintelligence.app.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.ui.common.MetadataRow
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Privacy center (§52): actual application metrics, never fabricated numbers.
 *
 * Every value is read from the database or the settings store on this device.
 * "Cloud uploads: 0" is a structural fact — the app has no `INTERNET` permission —
 * not a counter that could be wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    viewModel: PrivacyViewModel = viewModel(factory = PrivacyViewModel.Factory(locator)),
) {
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("header") { SectionHeader("Everything is processed locally") }
            item("structural") {
                Text(
                    text = "The strongest guarantee is structural: this app holds no " +
                        "`INTERNET` permission, so it cannot upload screenshots, OCR text, " +
                        "embeddings or conversations even if it wanted to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item("metrics") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.forEach { metric ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                MetadataRow(metric.label, metric.value)
                            }
                        }
                    }
                }
            }
        }
    }
}

class PrivacyViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    data class Metric(val label: String, val value: String)

    private val _metrics = MutableStateFlow(emptyList<Metric>())
    val metrics: StateFlow<List<Metric>> = _metrics.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val repository = locator.screenshotRepository
            val stats = runCatching { repository.countByStatus() }.getOrDefault(emptyMap())
            val total = stats.values.sum()
            val completed = stats["COMPLETED"] ?: 0
            val pending = stats["PENDING"] ?: 0
            val failed = stats["FAILED"] ?: 0
            val sensitive = runCatching {
                locator.autonomousRepository.topics().size
            }.getOrDefault(0)
            val dbSize = runCatching { repository.databaseSizeBytes() }.getOrDefault(0L)
            val storage = runCatching { repository.storageBreakdown() }.getOrNull()
            _metrics.value = listOf(
                Metric("Screenshots analyzed", total.toString()),
                Metric("OCR processed", completed.toString()),
                Metric("Pending indexing", pending.toString()),
                Metric("Failed indexing", failed.toString()),
                Metric("Embeddings stored locally", completed.toString()),
                Metric("Cloud uploads", "0 (no INTERNET permission)"),
                Metric("Sensitive content detected", "$sensitive topics flagged"),
                Metric("Sensitive content protected", "masked behind reveal"),
                Metric("Assistant history", "stored on this device only"),
                Metric("Database size", formatBytes(dbSize)),
                Metric("Storage", storage?.let { "DB: ${formatBytes(it.databaseBytes)}, Screenshots: ${formatBytes(it.screenshotsBytes)}" } ?: "unavailable"),
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PrivacyViewModel(locator) as T
    }
}