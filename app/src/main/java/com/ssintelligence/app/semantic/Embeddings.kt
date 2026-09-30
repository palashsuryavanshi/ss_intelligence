package com.ssintelligence.app.semantic

/**
 * A dense vector representing the meaning of a piece of text (§5).
 *
 * Vectors are always L2-normalized, so cosine similarity is a plain dot
 * product. Normalizing once at creation keeps every later comparison cheap.
 */
data class TextEmbedding(
    val values: FloatArray,
    val model: String,
    val version: String,
) {
    val dimension: Int get() = values.size

    /** Cosine similarity. Both sides must come from the same model version. */
    fun similarityTo(other: TextEmbedding): Double {
        require(model == other.model && version == other.version) {
            "Incompatible embeddings: $model v$version vs ${other.model} v${other.version}. " +
                "Old embeddings must be rebuilt, never mixed (§36)."
        }
        require(values.size == other.values.size) { "Dimension mismatch" }
        var dot = 0.0
        for (i in values.indices) dot += values[i] * other.values[i]
        return dot.coerceIn(-1.0, 1.0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextEmbedding) return false
        return model == other.model && version == other.version && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = 31 * (31 * model.hashCode() + version.hashCode()) + values.contentHashCode()
}

/**
 * Turns text into vectors, on this device (§5).
 *
 * The application is written against this interface, not against any one
 * model: a neural embedding model can replace the built-in provider later
 * without touching callers, storage, or ranking. [model] and [version]
 * identify the weights so incompatible vectors are never mixed (§36).
 */
interface EmbeddingProvider {
    val model: String
    val version: String
    val dimension: Int

    /** True when this provider can run right now (model present, RAM available). */
    val isAvailable: Boolean

    suspend fun embed(text: String): TextEmbedding
}

/**
 * Built-in deterministic text embeddings.
 *
 * No model file, no download, no network, no warmup: a hashed bag of word
 * unigrams, word bigrams and character trigrams, TF-weighted and L2
 * normalized. This is the classic feature-hashing construction — a genuine
 * vector space where cosine similarity measures overlap in subword features,
 * which is exactly what makes it tolerant to OCR misreads ("Pixcl" still
 * shares most trigrams with "Pixel"), plurals, and compounding.
 *
 * What it is not: a neural model. It cannot know that "phone" and "smartphone"
 * are related — that jump comes from [ConceptExpander], which is explicit and
 * reviewable rather than emergent. The combination is honest about what each
 * half contributes, and the [EmbeddingProvider] seam means a neural model can
 * take over the vector half later without changing anything else.
 *
 * Hashing uses a fixed FNV-1a 32-bit hash, not `String.hashCode`, so vectors
 * are stable across processes, devices and Kotlin versions. Stability matters:
 * embeddings are persisted, and a hash function that changed between runs
 * would silently invalidate the whole index.
 */
class HashedNgramEmbeddingProvider(
    override val dimension: Int = DEFAULT_DIMENSION,
) : EmbeddingProvider {

    override val model: String = MODEL_NAME
    override val version: String = MODEL_VERSION
    override val isAvailable: Boolean = true

    override suspend fun embed(text: String): TextEmbedding {
        val vector = FloatArray(dimension)
        val features = featuresOf(text)
        if (features.isEmpty()) return TextEmbedding(vector, model, version)
        val counts = features.groupingBy { it }.eachCount()
        for ((feature, count) in counts) {
            val index = (fnv1a(feature) % dimension).toInt().let { if (it < 0) it + dimension else it }
            // TF weight with a log damp so a repeated word cannot dominate.
            vector[index] += (1.0 + kotlin.math.ln(count.toDouble())).toFloat()
        }
        var norm = 0.0
        for (value in vector) norm += value * value
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        }
        return TextEmbedding(vector, model, version)
    }

    /**
     * Word unigrams and bigrams plus character trigrams of each word.
     *
     * Words carry topic ("flight", "ticket"); bigrams carry short phrases
     * ("boarding pass"); trigrams carry typo tolerance ("pixcl" ≈ "pixel").
     */
    internal fun featuresOf(text: String): List<String> {
        val words = WORD_SPLIT.split(text.lowercase())
            .map { it.filter { c -> c.isLetterOrDigit() } }
            .filter { it.length >= 2 }
            .take(MAX_WORDS)
        if (words.isEmpty()) return emptyList()
        return buildList {
            addAll(words)
            for (i in 0 until words.size - 1) add("${words[i]} ${words[i + 1]}")
            for (word in words) {
                val padded = "^$word$"
                for (i in 0..padded.length - 3) add(padded.substring(i, i + 3))
            }
        }
    }

    private fun fnv1a(input: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (c in input) {
            hash = hash xor c.code.toLong()
            hash = (hash * FNV_PRIME) and 0xFFFFFFFFL
        }
        return hash
    }

    companion object {
        const val MODEL_NAME = "hashed-ngram"
        const val MODEL_VERSION = "1"
        const val DEFAULT_DIMENSION = 512

        /**
         * 512 floats = 2 KB per screenshot. 10,000 screenshots embed to ~20 MB,
         * which is why vectors are loaded only for the prefiltered candidate
         * set, never for the whole library (§10).
         */
        const val BYTES_PER_EMBEDDING = DEFAULT_DIMENSION * 4

        private const val MAX_WORDS = 400
        private val WORD_SPLIT = Regex("\\s+")
        private const val FNV_OFFSET_BASIS = 0x811C9DC5L
        private const val FNV_PRIME = 0x01000193L
    }
}
