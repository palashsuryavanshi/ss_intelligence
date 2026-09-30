package com.ssintelligence.app.search.parser

/**
 * Understands one-time-code queries (§15).
 *
 * A bare number is never treated as a code. There must be an explicit context
 * word — "OTP", "one-time code", "verification code" — before digits are read
 * as a code, so `987654` in a search box stays a number (§44).
 *
 * Privacy rules that travel with the result:
 * - Codes are matched against `extracted_otps.code` but the value is never
 *   displayed. The UI labels such a match only as "One-time code".
 * - Codes are never used for autocomplete, suggestions, or search history
 *   entries: a suggestion or a history row is a place a secret would leak.
 *   [QueryParser] enforces this by dropping code-bearing queries from history.
 */
class OtpQueryParser {

    /**
     * Context words. Kept tight and explicit: a code must be *labelled* to be
     * searched for, never guessed at.
     */
    private val labelledCode = Regex(
        """(?i)\b(?:otp|o\.t\.p|one[\s-]?time(?:\s+(?:password|code|pin|otp))?|""" +
            """verification\s+code|verify\s+code|security\s+code|login\s+code|auth\s+code|""" +
            """2fa\s+code|mfa\s+code|captcha|pin)\b[\s:#.\-]*(\d{4,8})\b""",
    )

    /** Same context words, used to recognize "show me my OTPs" (no value). */
    private val contextWord = Regex(
        """(?i)\b(?:otp|o\.t\.p)s?\b|\bone[\s-]?time\s+(?:passwords?|codes?|pins?)\b|""" +
            """\b(?:verification|verify|security|login|auth|2fa|mfa)\s+codes?\b""",
    )

    fun parse(text: String, consumed: List<QuerySpan> = emptyList()): List<ParsedSpan<String>> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<ParsedSpan<String>>()
        for (match in labelledCode.findAll(text)) {
            val code = match.groupValues[1]
            if (code.length < 4) continue
            val start = match.range.first
            val end = match.range.last + 1
            val span = QuerySpan(start, end)
            if (consumed.any { it.overlaps(span) }) continue
            out += ParsedSpan(code, span)
        }
        return out
    }

    /** True when the query names one-time codes without giving a value. */
    fun mentionsCodes(text: String): Boolean = contextWord.containsMatchIn(text)
}
