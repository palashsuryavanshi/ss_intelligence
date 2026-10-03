package com.ssintelligence.app.security

/**
 * Local redaction of sensitive patterns before sharing/exporting (§29).
 *
 * Redaction modifies the shared/exported copy, never the original.
 * Currently supports text redaction; image region redaction is planned.
 */
object RedactionManager {

    private val emailPattern = Regex("""\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}\b""")
    private val phonePattern = Regex("""\b[+]?[\d\s()-]{7,15}\b""")
    private val otpPattern = Regex("""\b\d{4,8}\b""")
    private val cardPattern = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val panPattern = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val aadhaarPattern = Regex("""\b\d{4}[ -]?\d{4}[ -]?\d{4}\b""")
    private val upiPattern = Regex("""\b\w+@[\w.-]*(?:upi|apl|paytm|okaxis|ybl|sbi|okicici|oksbi|hdfcbank|icici)\b""")

    /**
     * Redacts sensitive patterns from text, replacing with [REDACTED].
     */
    fun redact(text: String): String {
        var result = text
        result = emailPattern.replace(result) { "<email redacted>" }
        result = phonePattern.replace(result) { "<phone redacted>" }
        result = cardPattern.replace(result) { "<card redacted>" }
        result = panPattern.replace(result) { "<PAN redacted>" }
        result = aadhaarPattern.replace(result) { "<ID redacted>" }
        result = upiPattern.replace(result) { "<UPI redacted>" }
        // OTP redaction last (broadest pattern, may catch phone fragments)
        result = otpPattern.replace(result) { "••••" }
        return result
    }

    /**
     * Detects whether text contains redactable sensitive content.
     */
    fun containsSensitive(text: String): Boolean {
        return emailPattern.containsMatchIn(text) ||
            phonePattern.containsMatchIn(text) ||
            cardPattern.containsMatchIn(text) ||
            panPattern.containsMatchIn(text) ||
            aadhaarPattern.containsMatchIn(text) ||
            upiPattern.containsMatchIn(text) ||
            otpPattern.containsMatchIn(text)
    }

    /**
     * Returns a summary of detected sensitive types.
     */
    fun detectSensitiveTypes(text: String): List<String> {
        val types = mutableListOf<String>()
        if (emailPattern.containsMatchIn(text)) types += "Email"
        if (phonePattern.containsMatchIn(text)) types += "Phone number"
        if (cardPattern.containsMatchIn(text)) types += "Card number"
        if (panPattern.containsMatchIn(text)) types += "PAN"
        if (aadhaarPattern.containsMatchIn(text)) types += "Aadhaar"
        if (upiPattern.containsMatchIn(text)) types += "UPI ID"
        if (otpPattern.containsMatchIn(text)) types += "OTP/code"
        return types
    }
}