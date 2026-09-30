package com.ssintelligence.app.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screenshot detection heuristics (§6).
 *
 * These rules decide what gets indexed, so they are pinned by tests: a missed
 * screenshot is invisible to the user, and a false positive wastes OCR time.
 */
class ScreenshotHeuristicsTest {

    @Test
    fun `recognises a standard screenshots folder`() {
        assertTrue(
            ScreenshotHeuristics.looksLikeScreenshot("Screenshot_20260930.png", "Pictures/Screenshots/")
        )
    }

    @Test
    fun `recognises a dcim screenshots folder`() {
        assertTrue(
            ScreenshotHeuristics.looksLikeScreenshot("Screenshot_20260930.png", "DCIM/Screenshots/")
        )
    }

    @Test
    fun `recognises a bare screenshots folder`() {
        assertTrue(
            ScreenshotHeuristics.looksLikeScreenshot("Screenshot_20260930.png", "Screenshots/")
        )
    }

    @Test
    fun `recognises a manufacturer specific screenshot folder`() {
        // Not every OEM uses Pictures/Screenshots.
        assertTrue(
            ScreenshotHeuristics.looksLikeScreenshot("Screenshot_20260930.png", "OnePlus/Screenshots/")
        )
        assertTrue(
            ScreenshotHeuristics.looksLikeScreenshot("Screenshot_20260930.png", "ScreenShots/")
        )
    }

    @Test
    fun `recognises a screenshot by filename outside a screenshot folder`() {
        assertTrue(
            ScreenshotHeuristics.looksLikeScreenshot("Screenshot_2026-09-30-10-00-00.png", "Pictures/")
        )
    }

    @Test
    fun `recognises other screenshot filename conventions`() {
        assertTrue(ScreenshotHeuristics.looksLikeScreenshot("Screenshot 2026-09-30.png", "Downloads/"))
        assertTrue(ScreenshotHeuristics.looksLikeScreenshot("screenshot-2026.png", "Documents/"))
        assertTrue(ScreenshotHeuristics.looksLikeScreenshot("screen_shot_2026.png", "Documents/"))
        assertTrue(ScreenshotHeuristics.looksLikeScreenshot("Screenshot.png", "Documents/"))
    }

    @Test
    fun `ignores a screen recording`() {
        assertFalse(
            ScreenshotHeuristics.looksLikeScreenshot("ScreenRecording2026.mp4", "Movies/ScreenRecords/")
        )
        assertFalse(
            ScreenshotHeuristics.looksLikeScreenshot("record.mp4", "Movies/ScreenRecorder/")
        )
    }

    @Test
    fun `ignores unrelated images`() {
        assertFalse(ScreenshotHeuristics.looksLikeScreenshot("Vacation.jpg", "Pictures/"))
        assertFalse(ScreenshotHeuristics.looksLikeScreenshot("receipt.png", "Documents/"))
    }

    @Test
    fun `ignores unsupported file types`() {
        assertFalse(ScreenshotHeuristics.looksLikeScreenshot("Screenshot.mp4", "Pictures/Screenshots/"))
        assertFalse(ScreenshotHeuristics.looksLikeScreenshot("Screenshot.gif", "Pictures/Screenshots/"))
    }

    @Test
    fun `accepts the common screenshot formats`() {
        assertTrue(ScreenshotHeuristics.looksLikeScreenshot("Screenshot.png", "Pictures/"))
        assertTrue(ScreenshotHeuristics.looksLikeScreenshot("Screenshot.jpg", "Pictures/"))
        assertTrue(ScreenshotHeuristics.looksLikeScreenshot("Screenshot.webp", "Pictures/"))
    }

    @Test
    fun `a screenshot in an unusual folder is still detected by filename`() {
        assertTrue(
            ScreenshotHeuristics.looksLikeScreenshot(
                "Screenshot_20260930-101112.png",
                "Download/Bluetooth/Screenshots/",
            )
        )
    }
}
