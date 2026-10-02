package com.ssintelligence.app.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * App lock using Android BiometricPrompt / device credentials (§9).
 *
 * Does NOT store custom passwords — relies on Android's native authentication
 * facilities (BiometricPrompt + device credential fallback).
 */
class AppLockManager(
    private val context: Context,
) {

    /** Whether the device has biometric hardware enrolled. */
    fun canUseBiometric(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Whether the device has device credential (PIN/pattern/password) enrolled. */
    fun canUseDeviceCredential(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Whether any authentication method is available. */
    fun hasAuthMethod(): Boolean = canUseBiometric() || canUseDeviceCredential()

    /**
     * Show authentication prompt.
     *
     * @param activity the FragmentActivity to show the prompt on
     * @param onSuccess called when authentication succeeds
     * @param onError called with error message
     * @param onCancelled called when user cancels
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED -> onCancelled()
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onCancelled()
                    else -> onError(errString.toString())
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                // Do not expose intermediate failure to caller; user can retry
            }
        })

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Screenshot Intelligence")
            .setSubtitle("Authenticate to continue")
            .setNegativeButtonText("Cancel")

        if (canUseDeviceCredential()) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        }

        prompt.authenticate(builder.build())
    }
}