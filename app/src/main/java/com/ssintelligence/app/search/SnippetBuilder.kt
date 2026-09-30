package com.ssintelligence.app.search

import com.ssintelligence.app.domain.model.Screenshot

/**
 * A compact, matched window of OCR text (§26).
 *
 * Results show a snippet rather than the whole OCR dump: full text is long,
 * noisy, and — for a screenshot of a bank page or a chat — needlessly repeats
 * exactly the content the user is trying to find.
 */
data class Snippet(
    val text: String,
    /** Character ranges within [text] that matched a query term. */
    val highlights: List<IntRange>,
)

/**
 * Extracts a relevant window from OCR text.
 *
 * Pure Kotlin and deterministic so the behaviour can be unit-tested. Whitespace
 * is collapsed first, because OCR output puts a line break wherever the image
 * had one and a naive window would slice through a word.
 */
object SnippetBuilder {

    private val whitespace = Regex("\\s+")

    const val DEFAULT_LENGTH = 180

    /**
     * @param needles query terms and phrases; the earliest occurrence anchors
     *   the window so the reader sees the match without scrolling.
     */
    fun build(
        ocrText: String,
        needles: List<String>,
        maxLength: Int = DEFAULT_LENGTH,
    ): Snippet? {
        val collapsed = ocrText.replace(whitespace, " ").trim()
        if (collapsed.isEmpty()) return null
        val lower = collapsed.lowercase()

        var anchor = -1
        for (needle in needles) {
            val term = needle.lowercase().trim()
            if (term.isEmpty()) continue
            val index = lower.indexOf(term)
            if (index >= 0 && (anchor < 0 || index < anchor)) anchor = index
        }

        if (anchor < 0) {
            // No term matched (a pure price or date query, for example): lead
            // with the first line of text, which is usually the most useful.
            val head = collapsed.take(maxLength)
            return Snippet(if (head.length < collapsed.length) "$head…" else head, emptyList())
        }

        // Put the match about a third of the way in, so there is context before
        // it as well as after.
        val start = (anchor - maxLength / 3).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(collapsed.length)

        val windowStart = alignToWordStart(collapsed, start)
        val windowEnd = alignToWordEnd(collapsed, end)

        val prefix = if (windowStart > 0) "…" else ""
        val suffix = if (windowEnd < collapsed.length) "…" else ""
        val body = collapsed.substring(windowStart, windowEnd)
        val text = prefix + body + suffix

        val highlights = mutableListOf<IntRange>()
        val bodyLower = body.lowercase()
        for (needle in needles) {
            val term = needle.lowercase().trim()
            if (term.isEmpty()) continue
            var from = 0
            while (true) {
                val index = bodyLower.indexOf(term, from)
                if (index < 0) break
                highlights += IntRange(index, index + term.length - 1)
                from = index + term.length
                if (highlights.size >= MAX_HIGHLIGHTS) break
            }
            if (highlights.size >= MAX_HIGHLIGHTS) break
        }

        val offset = prefix.length
        return Snippet(
            text = text,
            highlights = highlights.map { IntRange(it.first + offset, it.last + offset) },
        )
    }

    private fun alignToWordStart(text: String, index: Int): Int {
        if (index <= 0) return 0
        var i = index
        while (i < text.length && !text[i].isWhitespace()) i++
        return i.coerceAtMost(text.length)
    }

    private fun alignToWordEnd(text: String, index: Int): Int {
        if (index >= text.length) return text.length
        var i = index
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i.coerceAtLeast(0)
    }

    private const val MAX_HIGHLIGHTS = 6
}
