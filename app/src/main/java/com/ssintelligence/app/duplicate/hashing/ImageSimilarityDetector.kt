package com.ssintelligence.app.duplicate

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Abstraction for duplicate detection (§19).
 *
 * Phase 1 ships only [ContentHashDetector] (exact duplicates). Future phases
 * can add `PerceptualHashDetector` (dHash/pHash for near-duplicates) and
 * `ImageEmbeddingDetector` without changing the indexing pipeline.
 */
interface ImageSimilarityDetector {
    /** Stable identifier for the image content at [uri]. */
    suspend fun fingerprint(uri: Uri): String
}

/**
 * Exact-duplicate detector: SHA-256 over the raw encoded bytes of the image
 * (§18). Two screenshots with the same [fingerprint] are byte-identical files.
 *
 * Note on scope: this hashes the encoded image, not the decoded pixels. Two
 * screenshots of identical visual content saved with different encoders will
 * hash differently — that is precisely the case a perceptual hash would catch
 * in a later phase, and the reason the interface exists.
 */
class ContentHashDetector(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
) : ImageSimilarityDetector {

    override suspend fun fingerprint(uri: Uri): String = withContext(ioDispatcher) {
        val digest = MessageDigest.getInstance("SHA-256")
        openStream(uri).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHexString()
    }

    private fun openStream(uri: Uri): InputStream = try {
        contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Unable to open image stream: $uri")
    } catch (e: SecurityException) {
        throw FileNotFoundException("Read access revoked for screenshot").initCause(e)
    }
}

internal fun ByteArray.toHexString(): String {
    val hexChars = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xFF
        out[i * 2] = hexChars[b ushr 4]
        out[i * 2 + 1] = hexChars[b and 0x0F]
    }
    return String(out)
}
