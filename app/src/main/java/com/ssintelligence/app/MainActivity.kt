package com.ssintelligence.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.ssintelligence.app.ui.SsIntelligenceAppRoot

/**
 * Single-activity host (§5). All screens are Compose destinations inside
 * [SsIntelligenceAppRoot].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val locator = ServiceLocator.install(applicationContext)
        val startDestination = intent?.data?.toAppRoute()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SsIntelligenceAppRoot(locator, startDestination = startDestination)
                }
            }
        }
    }
}

/**
 * Maps an `ssi://` deep link to an in-app route (§56).
 *
 * Only the known hosts are recognized; everything else falls back to home.
 * Nothing here parses arbitrary URLs — the scheme is internal, and each
 * branch maps to one explicit destination.
 */
private fun android.net.Uri.toAppRoute(): String? = when (host) {
    "search" -> "search"
    "assistant" -> "assistant"
    "browse" -> "browse"
    "insights" -> "insights"
    "tasks" -> "tasks"
    "expenses" -> "expenses"
    "actions" -> "actions"
    "automation" -> "automation"
    "screenshot" -> lastPathSegment?.toLongOrNull()?.let { "detail/$it" }
    "entity" -> lastPathSegment?.toLongOrNull()?.let { "entity/$it" }
    "collection" -> lastPathSegment?.toLongOrNull()?.let { "collections" }
    else -> null
}
