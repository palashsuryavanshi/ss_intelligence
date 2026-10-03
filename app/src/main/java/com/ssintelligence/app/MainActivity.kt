package com.ssintelligence.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        requestMaxRefreshRate()
        val locator = ServiceLocator.install(applicationContext)
        val startDestination = intent?.data?.toAppRoute()
        setContent {
            SsIntelligenceAppRoot(locator, startDestination = startDestination)
        }
    }

    /**
     * Requests the display's maximum refresh rate for this window.
     *
     * The hardware decides what is possible — a 60 Hz panel stays at 60 Hz —
     * but on 90/120 Hz panels this opts the app into the highest mode instead
     * of the power-saving default, so scrolling and transitions render at the
     * full frame rate the screen supports.
     */
    private fun requestMaxRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mode = display?.supportedModes?.maxByOrNull { it.refreshRate }
                if (mode != null) {
                    val attrs = window.attributes
                    attrs.preferredDisplayModeId = mode.modeId
                    window.attributes = attrs
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
                val mode = display.supportedModes.maxByOrNull { it.refreshRate }
                if (mode != null) {
                    val attrs = window.attributes
                    attrs.preferredDisplayModeId = mode.modeId
                    window.attributes = attrs
                }
            }
        } catch (_: Exception) {
            // Best effort only: refresh rate never affects correctness.
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
