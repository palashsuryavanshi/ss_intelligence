package com.ssintelligence.app.data.media

import com.ssintelligence.app.domain.model.MediaImage

/**
 * Discovers existing screenshots through MediaStore (§6).
 *
 * Implementations must be tolerant: a user may have screenshots in
 * `Pictures/Screenshots`, `DCIM/Screenshots`, a manufacturer folder such as
 * `Pictures/ScreenShots` or `OnePlus/Screenshots`, or have them filed
 * elsewhere entirely. Heuristics therefore live here rather than in a
 * hardcoded list of directories.
 */
interface ScreenshotSource {
    /** All candidate images, newest first, excluding the app's own captures. */
    suspend fun discoverImages(includeAllImages: Boolean): List<MediaImage>
}

/**
 * Heuristics for recognising a screenshot from MediaStore metadata.
 *
 * Pure Kotlin and package-visible so it can be unit-tested on the JVM.
 */
object ScreenshotHeuristics {

    private val screenshotFolders = listOf(
        "/screenshot", "/screenshots", "/screenshot_", "/screenshots_",
        "/screen shots", "/screen captures", "/screen recordings", "/capture",
    )

    // Filename conventions across AOSP, Samsung, OnePlus, Xiaomi, Oppo, Vivo,
    // Realme, Nothing and Motorola builds.
    private val screenshotPrefixes = listOf(
        "screenshot", "screen shot", "screencap", "screen_shot", "ss_", "scr_",
    )
    private val screenshotSuffixes = listOf(
        "screenshot", "screen shot", "screencap", "screen_shot", "capture",
    )

    /** Non-screenshot folder names that must not be treated as a match. */
    private val exclusionMarkers = listOf(
        "/screenrecord", "/screen recorder", "/screen_recorder",
        "/screensavers", "/screen saver", "/wallpaper", "/wallpapers",
    )

    fun looksLikeScreenshot(filename: String, relativePath: String?): Boolean {
        val lowerName = filename.trim().lowercase()
        val path = relativePath?.lowercase()

        if (path != null) {
            if (exclusionMarkers.any { path.contains(it) }) return false
        }

        val name = lowerName.substringBeforeLast('.')
        val extension = lowerName.substringAfterLast('.', "")
        if (extension !in SUPPORTED_EXTENSIONS) return false

        // 1) Folder signal — the strongest and most portable indicator.
        if (path != null && screenshotFolders.any { path.contains(it) }) return true

        // 2) Filename signal.
        if (screenshotPrefixes.any { name.startsWith(it) }) return true
        if (screenshotSuffixes.any { name.endsWith(it) }) return true
        if (name.contains("_screenshot") || name.contains("screenshot_")) return true

        return false
    }

    private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
}
