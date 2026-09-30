package com.ssintelligence.app.duplicate.hashing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Duplicate detection via content hash (§18, §19, §36).
 *
 * [ContentHasher] is deliberately Android-free so this runs as a fast local
 * JVM test rather than requiring an instrumented test.
 */
class ContentHasherTest {

    private fun hash(bytes: ByteArray) =
        ContentHasher.sha256(ByteArrayInputStream(bytes))

    private fun pattern(seed: Int, size: Int = 4096) = ByteArray(size) { ((it + seed) % 251).toByte() }

    @Test
    fun `identical images produce the same hash`() {
        val bytes = pattern(seed = 0)
        assertEquals(hash(bytes), hash(bytes))
    }

    @Test
    fun `different images produce different hashes`() {
        assertNotEquals(hash(pattern(seed = 0)), hash(pattern(seed = 1)))
    }

    @Test
    fun `a single differing byte changes the hash`() {
        val a = ByteArray(2048).also { it[100] = 1 }
        val b = ByteArray(2048).also { it[100] = 2 }
        assertNotEquals(hash(a), hash(b))
    }

    @Test
    fun `hash is a lowercase 64 character hex digest`() {
        val digest = hash(byteArrayOf(1, 2, 3))
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `hash is independent of read chunking`() {
        val bytes = pattern(seed = 7)
        val oneShot = hash(bytes)
        // Feeding the same bytes through a stream that yields 1 byte at a time
        // must not change the digest.
        val slow = ContentHasher.sha256(
            object : java.io.InputStream() {
                private var index = 0
                override fun read(): Int =
                    if (index >= bytes.size) -1 else bytes[index++].toInt() and 0xFF

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (index >= bytes.size) return -1
                    val n = minOf(1, len, bytes.size - index)
                    for (i in 0 until n) b[off + i] = bytes[index + i]
                    index += n
                    return n
                }
            }
        )
        assertEquals(oneShot, slow)
    }

    @Test
    fun `empty input still produces a valid digest`() {
        assertEquals(64, hash(ByteArray(0)).length)
    }
}
