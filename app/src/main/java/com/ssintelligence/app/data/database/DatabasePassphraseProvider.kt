package com.ssintelligence.app.data.database

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Provides a secure passphrase for SQLCipher database encryption.
 *
 * Uses Android Keystore to generate/store a SecretKey, which wraps a
 * random 32-byte database passphrase. The passphrase is stored encrypted
 * in SharedPreferences; the key in Keystore never leaves the TEE.
 *
 * On fresh install, generates a new passphrase. On upgrade, reuses the
 * same passphrase (database is not re-encrypted automatically; a migration
 * would be needed if moving from unencrypted to encrypted).
 */
class DatabasePassphraseProvider(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ss_db_key", Context.MODE_PRIVATE,
    )

    private val keyAlias = "ss_db_encryption_key"

    /**
     * Returns the database passphrase, creating and storing it if needed.
     */
    fun getPassphrase(): ByteArray {
        val encrypted = prefs.getString("encrypted_passphrase", null)
        val iv = prefs.getString("passphrase_iv", null)

        if (encrypted != null && iv != null) {
            return decryptPassphrase(Base64.decode(encrypted, Base64.DEFAULT), Base64.decode(iv, Base64.DEFAULT))
        }

        // Generate new passphrase
        val passphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val (encryptedBytes, ivBytes) = encryptPassphrase(passphrase)
        prefs.edit()
            .putString("encrypted_passphrase", Base64.encodeToString(encryptedBytes, Base64.DEFAULT))
            .putString("passphrase_iv", Base64.encodeToString(ivBytes, Base64.DEFAULT))
            .apply()
        return passphrase
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(keyAlias, null)?.let { return it as SecretKey }

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encryptPassphrase(passphrase: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(passphrase)
        return encrypted to iv
    }

    private fun decryptPassphrase(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}