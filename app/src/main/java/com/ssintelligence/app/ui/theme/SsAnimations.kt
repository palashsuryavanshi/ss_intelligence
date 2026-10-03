package com.ssintelligence.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
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

    /** Quick micro-interaction: 150ms */
    fun <T> quick(): AnimationSpec<T> = tween(
        durationMillis = 150,
        easing = androidx.compose.animation.core.FastOutSlowInEasing,
    )

    /** Standard transition: 300ms */
    fun <T> standard(): AnimationSpec<T> = tween(
        durationMillis = 300,
        easing = androidx.compose.animation.core.FastOutSlowInEasing,
    )

    /** Emphasis transition: 450ms for important state changes */
    fun <T> emphasis(): AnimationSpec<T> = tween(
        durationMillis = 450,
        easing = androidx.compose.animation.core.FastOutSlowInEasing,
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