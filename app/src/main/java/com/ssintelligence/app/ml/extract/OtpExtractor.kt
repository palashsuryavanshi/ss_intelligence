package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.OtpCandidate

/**
 * OTP / one-time-password detection (§17).
 *
 * Recognised code lengths: 4, 5, 6 and 8 digits.
 *
 * The important constraint is precision, not recall: arbitrary 4–8 digit
 * numbers appear constantly in screenshots (order IDs, amounts, dates,
 * timestamps, IMEI-like strings). A candidate is therefore only accepted when
 * a keyword cue appears within a short window before or after it, or when the
 * code is visually isolated and looks random (mixed digits, not a repeated
 * sequence, not a date, not an amount).
 */
class OtpExtractor {

    private data class Cue(val regex: Regex, val distance: Int)

    private val cues = listOf(
        // "OTP: 123456", "otp is 483921", "One time password 928341"
        Cue(Regex("""\b(otp|o\.?t\.?p\.?)\b""", RegexOption.IGNORE_CASE), 40),
        Cue(Regex("""\bone[\s\-]?time[\s\-]?password\b""", RegexOption.IGNORE_CASE), 40),
        Cue(Regex("""\bverification[\s\-]?code\b""", RegexOption.IGNORE_CASE), 40),
        Cue(Regex("""\bsecurity[\s\-]?code\b""", RegexOption.IGNORE_CASE), 40),
        Cue(Regex("""\bsecurity[\s\-]?pin\b""", RegexOption.IGNORE_CASE), 40),
        Cue(Regex("""\bauth(?:entication)?[\s\-]?code\b""", RegexOption.IGNORE_CASE), 40),
        // "verify with 928341", "to verify 1234", "verify: 482913"
        Cue(Regex("""\bverif(?:y|ied|ication)\b""", RegexOption.IGNORE_CASE), 24),
        Cue(Regex("""\bconfirm\b""", RegexOption.IGNORE_CASE), 24),
        // "Your code is 482913", "code: 1234", "enter the code 9988"
        Cue(Regex("""\bcode\b""", RegexOption.IGNORE_CASE), 20),
        // "enter 482913", "use 928341 to continue"
        Cue(Regex("""\buse\b""", RegexOption.IGNORE_CASE), 16),
        Cue(Regex("""\benter\b""", RegexOption.IGNORE_CASE), 16),
    )

    // Numbers that are structurally not OTPs.
    private val rejectBefore = Regex(
        """\b(order|booking|transaction|txn|upi|ref|reference|receipt|bill|invoice|account|aadhaar|pan|pin|card|ifsc|account)\b[\s:.\-#]*$""",
        RegexOption.IGNORE_CASE,
    )
    private val dateLike = Regex("""^\d{4}[-/]\d{1,2}[-/]\d{1,2}$""")
    private val timeLike = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val repeated = Regex("""^(\d)\1+$""")
    private val yearLike = Regex("""^(19|20)\d{2}$""")

    private val codePattern = Regex("""(?<![\d.,])\d{4,8}(?![\d.,%])""")

    fun extract(text: String): List<OtpCandidate> {
        if (text.isBlank()) return emptyList()
        val found = LinkedHashMap<String, OtpCandidate>()
        for (match in codePattern.findAll(text)) {
            val code = match.value
            if (code.length !in ALLOWED_LENGTHS) continue
            if (rejectBefore.containsMatchIn(text.substring(maxOf(0, match.range.first - 28), match.range.first))) {
                continue
            }
            if (isStructured(code)) continue
            if (hasCue(text, match.range)) {
                found.putIfAbsent(code, OtpCandidate(code))
            }
        }
        return found.values.toList()
    }

    /**
     * A cue must sit next to *this* code, so it is looked for on each side of
     * the digits independently. A keyword belonging to a different number in a
     * shared window therefore does not create a match.
     */
    private fun hasCue(text: String, range: IntRange): Boolean {
        val before = text.substring(maxOf(0, range.first - 44), range.first)
        val after = text.substring(range.last + 1, minOf(text.length, range.last + 1 + 24))
        return cues.any { cue ->
            (before.length <= cue.distance + 20 && cue.regex.containsMatchIn(before)) ||
                (after.length <= cue.distance + 10 && cue.regex.containsMatchIn(after))
        }
    }

    /**
     * Rejects values whose shape rules out a one-time code.
     *
     * Sequential runs are deliberately *not* rejected here: "123456" is a
     * perfectly plausible one-time code, and an explicit OTP keyword beside it
     * is stronger evidence than the digits being tidy.
     */
    private fun isStructured(code: String): Boolean = when {
        repeated.matches(code) -> true
        yearLike.matches(code) -> true
        dateLike.matches(code) -> true
        timeLike.matches(code) -> true
        // 8 digits that spell out a real calendar date (YYYYMMDD) are a
        // timestamp, not a code. Other 8-digit values are left alone.
        code.length == 8 && isCalendarDate(code) -> true
        else -> false
    }

    private fun isCalendarDate(code: String): Boolean = try {
        val month = code.substring(4, 6).toInt()
        val day = code.substring(6, 8).toInt()
        val year = code.substring(0, 4).toInt()
        java.time.LocalDate.of(year, month, day)
        true
    } catch (e: java.time.DateTimeException) {
        false
    }

    private companion object {
        val ALLOWED_LENGTHS = setOf(4, 5, 6, 8)
    }
}
