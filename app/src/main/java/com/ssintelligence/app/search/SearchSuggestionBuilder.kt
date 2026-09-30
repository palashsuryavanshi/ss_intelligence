package com.ssintelligence.app.search

import com.ssintelligence.app.data.database.ScreenshotDao
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Local autocomplete (§38).
 *
 * Every suggestion is something already on this device:
 * 1. the user's own recent searches,
 * 2. hosts that appear in indexed screenshots,
 * 3. short phrases mined out of OCR text by matching the typed prefix.
 *
 * There is no model, no frequency table shipped with the app, and no network
 * call. Typing a prefix never leaves the process: the prefix is turned into a
 * local FTS query and the rows it matches are already indexed.
 *
 * One-time codes are excluded by construction — the code table is not consulted
 * and recent searches that mentioned a code were never stored (§15).
 */
class SearchSuggestionBuilder(
    private val dao: ScreenshotDao,
    private val history: SearchHistoryRepository,
) {

    fun observe(prefix: String): Flow<List<SearchSuggestion>> = flow {
        val trimmed = prefix.trim()
        if (trimmed.length < MIN_PREFIX_CHARS) {
            emit(emptyList())
            return@flow
        }
        val lower = trimmed.lowercase()
        val out = linkedMapOf<String, SearchSuggestion>()

        history.observeRecent(HISTORY_SCAN)
            .first()
            .asSequence()
            .filter { it.lowercase().startsWith(lower) }
            .forEach { put(out, it, SuggestionSource.RECENT) }

        dao.topHosts(HOST_SCAN)
            .asSequence()
            .map { it.host }
            .filter { it.startsWith(lower) }
            .forEach { put(out, it, SuggestionSource.DOMAIN) }

        val match = FtsQueryBuilder.buildFromTerms(listOf(lower))
        if (match != null) {
            val mined = mutableListOf<String>()
            for (sample in dao.ocrForPrefix(match, OCR_SCAN)) {
                minePhrases(sample, lower, mined)
            }
            mined.forEach { put(out, it, SuggestionSource.OCR) }
        }

        emit(out.values.take(MAX_SUGGESTIONS).toList())
    }.flowOn(Dispatchers.IO)

    private fun put(
        into: MutableMap<String, SearchSuggestion>,
        text: String,
        source: SuggestionSource,
    ) {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return
        // First source wins, which orders the list recent → domain → OCR.
        into.putIfAbsent(cleaned.lowercase(), SearchSuggestion(cleaned, source))
    }

    /**
     * Expands a prefix occurrence into a short phrase: the word it starts plus
     * the next one, which is how "pix" becomes "pixel 9a" rather than a bare
     * "pixel" repeated from every screenshot.
     */
    private fun minePhrases(text: String, prefix: String, into: MutableList<String>) {
        val lower = text.lowercase()
        var from = 0
        var taken = 0
        while (taken < PHRASES_PER_DOCUMENT) {
            val index = lower.indexOf(prefix, from)
            if (index < 0) return
            from = index + prefix.length
            // Must start a word, otherwise "pix" inside "pixelated" would match.
            if (index > 0 && (lower[index - 1].isLetterOrDigit() || lower[index - 1] == '_')) {
                continue
            }
            taken++
            val phrase = text.substring(index)
                .split(Regex("\\s+"))
                .take(PHRASE_WORDS)
                .joinToString(" ") { it.trim(*TRIM_CHARS) }
                .trim()
            if (phrase.isNotEmpty() && !phrase.all { it.isDigit() }) {
                into += phrase
            }
        }
    }

    private companion object {
        const val MIN_PREFIX_CHARS = 2
        const val MAX_SUGGESTIONS = 6
        const val HISTORY_SCAN = 40
        const val HOST_SCAN = 200
        const val OCR_SCAN = 12
        const val PHRASES_PER_DOCUMENT = 3
        const val PHRASE_WORDS = 2
        val TRIM_CHARS = ".,;:!?\"'()[]{}".toCharArray()
    }
}
