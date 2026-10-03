package com.ssintelligence.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.provider.Settings

/**
 * Centralized animation configuration (§19).
 *
 * Fast + subtle + purposeful. No excessive bouncing or slow transitions.
 * Respects Android's reduced-motion accessibility setting.
 */
object SsAnimations {

    /** Whether the user has enabled reduced motion in Android settings. */
    @Composable
    @ReadOnlyComposable
    fun isReducedMotion(): Boolean {
        val context = LocalContext.current
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            ) == 0.0f
        } catch (_: Exception) {
            false
        }
    }

    /** Non-composable check for places without composition (e.g. nav transitions). */
    fun isReducedMotionEnabled(context: android.content.Context): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            ) == 0.0f
        } catch (_: Exception) {
            false
        }
    }

    /** Quick micro-interaction: 150ms */
    fun <T> quick(): AnimationSpec<T> = tween(
        durationMillis = SsMotion.Fast,
        easing = SsMotion.EaseOut,
    )

    /** Standard transition: 300ms */
    fun <T> standard(): AnimationSpec<T> = tween(
        durationMillis = SsMotion.Medium,
        easing = SsMotion.EaseOut,
    )

    /** Emphasis transition: 450ms for important state changes */
    fun <T> emphasis(): AnimationSpec<T> = tween(
        durationMillis = SsMotion.Long,
        easing = SsMotion.EaseOut,
    )

    /** Spring for natural, physical motion */
    fun <T> natural(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** Gentle spring for subtle bounce */
    fun <T> gentle(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Snappy spring for responsive feedback */
    fun <T> snappy(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Returns the appropriate spec based on reduced-motion preference. */
    @Composable
    @ReadOnlyComposable
    fun <T> responsive(base: AnimationSpec<T>): AnimationSpec<T> {
        return if (isReducedMotion()) {
            tween(0) // Instant when reduced motion is enabled
        } else {
            base
        }
    }
}

/**
 * Animation design tokens (§30).
 *
 * Single source of truth for durations, easings, distances and scales.
 * No screen should hardcode animation numbers.
 */
object SsMotion {
    // Durations (ms)
    const val Fast = 120
    const val Standard = 220
    const val Medium = 300
    const val Long = 400

    // Screen navigation
    const val ScreenEnter = 250
    const val ScreenExit = 200

    // Easing curves: ease-out on enter, ease-in on exit, ease-in-out for transforms
    val EaseOut: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val EaseIn: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    // Distances: small movements only (4–24dp)
    val FadeSlideDistance: Dp = 16.dp
    val ListSlideDistance: Dp = 24.dp
    val SheetSlideDistance: Dp = 8.dp

    // Dialog appearance: subtle scale + fade
    const val DialogScaleFrom = 0.96f
    const val DialogScaleTo = 1.0f

    // Button press: extremely subtle scale response
    const val PressScale = 0.98f
}