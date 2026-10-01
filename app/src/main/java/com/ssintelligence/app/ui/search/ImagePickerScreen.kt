package com.ssintelligence.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.ui.common.ScreenshotRow

/**
 * Picks the target for search-by-image (§7).
 *
 * The first implementation uses another screenshot already on the device — no
 * camera, no new permission. The picked image ranks the library by visual
 * similarity, combined with any text in the search box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePickerScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val recent by locator.screenshotRepository
        .observeRecent(80)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find shots like…") },
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
            items(recent, key = { it.id }) { screenshot ->
                ScreenshotRow(screenshot = screenshot, onClick = { onPick(screenshot.id) })
            }
        }
    }
}
