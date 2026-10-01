package com.ssintelligence.app.search.parser

import com.ssintelligence.app.vision.ColorAnalysis

/**
 * Understands palette and shape words in a search query (§9, §25 Phase 4).
 *
 * `blue`, `dark`, `long screenshots` — matched against the screenshot's
 * extracted palette and dimensions, never against object recognition the app
 * does not have. "Blue" means the image is mostly blue, whether that blue is
 * a sky, a button, or a phone, and the UI says exactly that.
 *
 * Runs before the keyword extractor so `blue` in "blue phone" becomes a color
 * filter rather than a search for the word "blue" in OCR text.
 */
class VisualQueryParser {

    private val longPattern = Regex(
        """(?i)\b(?:long|tall|stitched|full[\s-]?page|scrolling)\s+(?:screenshots?|images?|pics?|captures?|shots?)\b""",
    )

    data class Result(
        val colors: List<String>,
        val longOnly: Boolean,
        val spans: List<QuerySpan>,
    )

    fun parse(text: String, consumed: List<QuerySpan> = emptyList()): Result {
        if (text.isBlank()) return Result(emptyList(), false, emptyList())
        val colors = mutableListOf<String>()
        val spans = mutableListOf<QuerySpan>()

        for (match in longPattern.findAll(text)) {
            val span = QuerySpan(match.range.first, match.range.last + 1)
            if (consumed.any { it.overlaps(span) }) continue
            if (spans.any { it.overlaps(span) }) continue
            spans += span
        }
        val longOnly = spans.isNotEmpty()

        for (token in QueryTokenizer.tokenize(text)) {
            val color = ColorAnalysis.QUERY_WORDS[token.lower]?.label
                ?: token.lower.takeIf { it in ColorAnalysis.BRIGHTNESS_WORDS }
            if (color == null) continue
            val span = QuerySpan(token.start, token.end)
            if (consumed.any { it.overlaps(span) }) continue
            if (spans.any { it.overlaps(span) }) continue
            // "dark mode" and "light mode" are UI phrases, not palette asks:
            // claiming "dark" there would strip the meaningful half.
            if (isModePhrase(text, token)) continue
            colors += color
            spans += span
        }
        return Result(colors.distinct(), longOnly, spans)
    }

    private fun isModePhrase(text: String, token: QueryToken): Boolean {
        if (token.lower != "dark" && token.lower != "light") return false
        val after = text.substring(token.end).trimStart()
        return after.startsWith("mode", ignoreCase = true) &&
            after.drop(4).firstOrNull()?.let { !it.isLetter() } != false
    }
}
