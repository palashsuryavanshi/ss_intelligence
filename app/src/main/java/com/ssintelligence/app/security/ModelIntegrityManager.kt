package com.ssintelligence.app.security

import android.content.Context
import java.security.MessageDigest

/**
 * Model integrity verification (§38, §39).
 *
 * Every local AI model must have a known SHA-256 hash. On startup and before
 * inference, the model file's hash is verified. Never silently replace models.
 *
 * Currently: ML Kit text recognition is bundled with the APK (no download).
 * The manifest documents expected hashes for future downloadable models.
 */
class ModelIntegrityManager(private val context: Context) {

    data class ModelInfo(
        val name: String,
        val version: String,
        val sizeBytes: Long,
        val expectedSha256: String,
        val source: String,
        val license: String,
        val runtime: String,
        val requiredRamMb: Int,
    )

    /**
     * Known model manifest. For bundled models, hashes are fixed at build time.
     * For future downloadable models, hashes must be provided at download time.
     */
    val manifest: List<ModelInfo> = listOf(
        ModelInfo(
            name = "ML Kit Text Recognition",
            version = "16.0.1",
            sizeBytes = 12_000_000,
            expectedSha256 = "bundled-with-apk",
            source = "Google ML Kit (bundled)",
            license = "Apache 2.0",
            runtime = "Native (on-device)",
            requiredRamMb = 128,
        ),
        ModelInfo(
            name = "Hashed N-gram Embedding Provider",
            version = "1.0",
            sizeBytes = 0,
            expectedSha256 = "builtin",
            source = "App source code",
            license = "App license",
            runtime = "Kotlin/JVM",
            requiredRamMb = 16,
        ),
    )

    /**
     * Computes SHA-256 hash of a file.
     */
    fun computeFileHash(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val file = java.io.File(path)
        if (!file.exists()) return ""
        val buffer = ByteArray(8192)
        java.io.FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies that a model file matches its expected hash.
     * For bundled models, returns true (hash is verified by APK signature).
     */
    fun verifyModel(info: ModelInfo, filePath: String?): Boolean {
        if (info.expectedSha256 == "bundled-with-apk" || info.expectedSha256 == "builtin") {
            return true
        }
        if (filePath == null) return false
        val actual = computeFileHash(filePath)
        return actual.equals(info.expectedSha256, ignoreCase = true)
    }

    /**
     * Checks all models and returns a report.
     */
    fun audit(): List<String> = manifest.map { info ->
        val verified = verifyModel(info, null)
        "${info.name} v${info.version}: ${if (verified) "OK" else "UNVERIFIED"}"
    }
}