package com.ssintelligence.app.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Clipboard security (§12).
 *
 * When copying sensitive content, marks it with IS_SENSITIVE (Android 13+)
 * and optionally auto-clears it after a configurable timeout.
 */
class ClipboardSecurityManager(
    private val context: Context,
    private val privacyManager: PrivacyManager,
) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Copy text to clipboard with security protection.
     *
     * @param label clipboard label
     * @param text the text to copy
     * @param isSensitive whether this is sensitive content (OTP, password, etc.)
     */
    suspend fun copy(label: String, text: String, isSensitive: Boolean = false) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (isSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)

        if (isSensitive && privacyManager.isClipboardProtectionEnabled()) {
            scheduleClear(text)
        }
    }

    private fun scheduleClear(expected: String) {
        // Default timeout: 30 seconds
        val timeoutMs = 30_000L
        handler.postDelayed({
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val current = clipboard.primaryClip
                // Only clear if it still holds the same sensitive text
                if (current != null && current.itemCount > 0 &&
                    current.getItemAt(0)?.text == expected
                ) {
                    clipboard.clearPrimaryClip()
                }
            } catch (_: Exception) {
                // Best-effort clearing
            }
        }, timeoutMs)
    }

    suspend fun copyNormal(label: String, text: String) = copy(label, text, isSensitive = false)
    suspend fun copySensitive(label: String, text: String) = copy(label, text, isSensitive = true)
}