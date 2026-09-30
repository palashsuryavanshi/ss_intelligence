package com.ssintelligence.app.semantic

/**
 * Internal sensitive-content flags (§49).
 *
 * These are classification outputs, not user-facing labels. A flagged
 * screenshot is excluded from suggestions, autocomplete previews, smart-group
 * covers and any future notification or widget surface — places where content
 * appears without the user asking to see it. The flag never leaves the device
 * and never triggers any upload, share or deletion.
 */
enum class SensitiveKind(val label: String) {
    OTP("One-time code"),
    BANKING("Banking"),
    PAYMENT("Payment"),
    IDENTITY("Identity document"),
    PASSWORD("Password"),
    PRIVATE_CHAT("Private conversation"),
}

object SensitiveContentDetector {

    private val rules: List<Pair<SensitiveKind, Regex>> = listOf(
        SensitiveKind.OTP to Regex(
            """(?i)\b(?:otp|one[\s-]?time|verification\s+code|2fa|mfa)\b""",
        ),
        SensitiveKind.BANKING to Regex(
            """(?i)\b(?:bank|account\s+(?:number|no)|ifsc|netbanking|debit\s+card|credit\s+card|cvv|balance|statement)\b""",
        ),
        SensitiveKind.PAYMENT to Regex(
            """(?i)\b(?:upi|payment\s+successful|transaction\s+(?:id|successful)|paid\s+₹|wallet)\b""",
        ),
        SensitiveKind.IDENTITY to Regex(
            """(?i)\b(?:aadhaar|passport|pan\s+card|driving\s+licen[sc]e|voter\s+id|date\s+of\s+birth)\b""",
        ),
        SensitiveKind.PASSWORD to Regex(
            """(?i)\b(?:password|passcode|passwd|pwd)\b[\s:]*\S""",
        ),
        SensitiveKind.PRIVATE_CHAT to Regex(
            """(?i)\b(?:whatsapp|telegram)\b.{0,80}\b(?:love|secret|private|personal)\b""",
        ),
    )

    fun detect(document: ScreenshotDocument): Set<SensitiveKind> {
        if (document.ocrText.isBlank()) return emptySet()
        return rules
            .filter { (_, pattern) -> pattern.containsMatchIn(document.ocrText) }
            .map { it.first }
            .toSet()
    }
}
