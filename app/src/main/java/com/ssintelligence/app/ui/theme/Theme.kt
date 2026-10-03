package com.ssintelligence.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ssintelligence.app.domain.model.ThemeMode

/*
 * AMOLED-first design system (§2, §3, §26).
 *
 * True black backgrounds, deep navy surfaces, subtle blue accents.
 * Blue is reserved for interactive elements, active states, and progress.
 * The majority of the interface remains black and dark navy.
 */

// Core palette
object SsColors {
    // Backgrounds
    val Background = Color(0xFF000000)           // True AMOLED black
    val BackgroundSecondary = Color(0xFF050912)  // Extremely dark navy
    val Surface = Color(0xFF08111F)              // Dark navy surface
    val SurfaceElevated = Color(0xFF0B1628)      // Elevated surface
    val SurfaceVariant = Color(0xFF0E1A2E)       // Slightly lighter navy

    // Navy blues
    val NavyPrimary = Color(0xFF0A3D91)          // Primary navy
    val NavyAccent = Color(0xFF1456C0)           // Accent navy/blue
    val NavyBright = Color(0xFF1E6FDB)           // Bright accent for interactive

    // Text
    val TextPrimary = Color(0xFFF1F5F9)          // Near-white
    val TextSecondary = Color(0xFF94A3B8)        // Cool gray
    val TextDisabled = Color(0xFF475569)         // Muted gray-blue
    val TextOnNavy = Color(0xFFE2E8F0)           // Text on navy backgrounds

    // Lines and borders
    val Divider = Color(0xFF1E293B)              // Subtle dark navy-gray
    val Outline = Color(0xFF334155)              // Slightly more visible border

    // Semantic
    val Error = Color(0xFFEF4444)
    val ErrorContainer = Color(0xFF7F1D1D)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
}

private val AmoledDarkColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF003064),
    primaryContainer = Color(0xFF0A3D91),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF1E293B),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFCBD5E1),
    tertiary = Color(0xFF22D3EE),
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF0B3D44),
    onTertiaryContainer = Color(0xFFB8EDF5),
    error = Color(0xFFEF4444),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF050912),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF0B1628),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A3D91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF0E7490),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB8EDF5),
    onTertiaryContainer = Color(0xFF042F36),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
)

@Composable
fun SsIntelligenceTheme(
    themeMode: ThemeMode,
    /** Dynamic color is opt-out so screenshots of the app stay on-brand. */
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> AmoledDarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SsTypography,
        content = content,
    )
}