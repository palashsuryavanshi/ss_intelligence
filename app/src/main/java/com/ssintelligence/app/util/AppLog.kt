package com.ssintelligence.app.util

import android.util.Log
import com.ssintelligence.app.BuildConfig

/**
 * Structured, privacy-aware logging.
 *
 * Rules (see also §35 / §40 of the product spec):
 * - Never log OCR text, phone numbers, OTP codes, URLs, or filenames that may
 *   contain personal information. Log IDs and counts instead.
 * - Good: "OCR completed for screenshotId=123 characters=482"
 * - Bad:  "OCR RESULT: Your OTP is 839291"
 * - All output is stripped in release builds (no-op + R8 assumenosideeffects).
 */
object AppLog {
    private inline fun logIfDebug(block: () -> Unit) {
        if (BuildConfig.DEBUG) block()
    }

    fun d(tag: String, message: String) = logIfDebug { Log.d(tag, message) }
    fun i(tag: String, message: String) = logIfDebug { Log.i(tag, message) }
    fun w(tag: String, message: String) = logIfDebug { Log.w(tag, message) }
    fun w(tag: String, message: String, throwable: Throwable) =
        logIfDebug { Log.w(tag, message, throwable) }

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        logIfDebug { Log.e(tag, message, throwable) }
}
