package com.ssintelligence.app.security

import com.ssintelligence.app.domain.model.ExtractedReceipt

/**
 * Deterministic sensitive-content detection (§3, §4).
 *
 * Uses regex and context keywords to classify OCR text and extracted entities
 * without relying on AI. Never claims perfect accuracy — confidence levels
 * guide conservative handling.
 */
object SensitiveContentDetector {

    enum class SensitivityLevel {
        PUBLIC,
        PERSONAL,
        SENSITIVE,
        HIGHLY_SENSITIVE,
        AUTHENTICATION,
        FINANCIAL,
        IDENTITY,
        PRIVATE_COMMUNICATION,
    }

    data class Classification(
        val level: SensitivityLevel,
        val reasons: List<String>,
        val confidence: Float,
    )

    // OTP patterns: 4-8 digits, often near keywords
    private val OTP_PATTERN = Regex("""\b(?:otp|otp\s+code|verification\s+code|auth\s+code|2fa|two-factor|authenticator)[:\s]*(\d{4,8})\b""", RegexOption.IGNORE_CASE)
    private val OTP_DIGITS_PATTERN = Regex("""\b\d{6}\b""")
    private val PASSWORD_PATTERN = Regex("""\b(?:password|pwd|pass|pin|cvv|cvc)[:\s]*\S{4,20}""", RegexOption.IGNORE_CASE)

    // Financial patterns
    private val UPI_PATTERN = Regex("""\b\w+@[\w.-]*(?:upi|apl|paytm|okaxis|ybl|sbi|okicici|oksbi|hdfcbank|icici)\b""", RegexOption.IGNORE_CASE)
    private val CARD_NUMBER_PATTERN = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val ACCOUNT_NUMBER_PATTERN = Regex("""\b\d{9,18}\b""")
    private val IFSC_PATTERN = Regex("""\b[A-Z]{4}0[A-Z0-9]{6}\b""")
    private val TRANSACTION_ID_PATTERN = Regex("""\b(?:txn|transaction|ref|reference|id)[:\s#]*\S{6,20}""", RegexOption.IGNORE_CASE)

    // Identity patterns
    private val AADHAAR_PATTERN = Regex("""\b\d{4}[ -]?\d{4}[ -]?\d{4}\b""")
    private val PAN_PATTERN = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val PASSPORT_PATTERN = Regex("""\b[A-Z]{1,2}\d{6,7}\b""")

    // Personal patterns
    private val EMAIL_PATTERN = Regex("""\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}\b""")
    private val PHONE_PATTERN = Regex("""\b[+]?[\d\s()-]{7,15}\b""")

    fun classify(
        ocrText: String,
        entities: List<String> = emptyList(),
        hasPrices: Boolean = false,
        hasOtp: Boolean = false,
        hasReceipt: Boolean = false,
    ): Classification {
        val reasons = mutableListOf<String>()
        var level = SensitivityLevel.PUBLIC
        var confidence = 0.5f

        // Authentication detection
        if (hasOtp || OTP_PATTERN.containsMatchIn(ocrText) || OTP_DIGITS_PATTERN.containsMatchIn(ocrText)) {
            level = SensitivityLevel.AUTHENTICATION
            reasons += "OTP/verification code detected"
            confidence = 0.9f
        }
        if (PASSWORD_PATTERN.containsMatchIn(ocrText)) {
            level = SensitivityLevel.AUTHENTICATION
            reasons += "Password-like content detected"
            confidence = 0.85f
        }

        // Financial detection
        if (UPI_PATTERN.containsMatchIn(ocrText)) {
            level = SensitivityLevel.FINANCIAL
            reasons += "UPI ID detected"
            confidence = 0.9f
        }
        if (CARD_NUMBER_PATTERN.containsMatchIn(ocrText)) {
            level = SensitivityLevel.FINANCIAL
            reasons += "Card number pattern detected"
            confidence = 0.85f
        }
        if (ACCOUNT_NUMBER_PATTERN.containsMatchIn(ocrText) && IFSC_PATTERN.containsMatchIn(ocrText)) {
            level = SensitivityLevel.FINANCIAL
            reasons += "Bank account details detected"
            confidence = 0.9f
        }
        if (hasPrices || TRANSACTION_ID_PATTERN.containsMatchIn(ocrText)) {
            if (level == SensitivityLevel.PUBLIC) level = SensitivityLevel.FINANCIAL
            reasons += "Financial information detected"
            confidence = maxOf(confidence, 0.6f)
        }
        if (hasReceipt) {
            level = SensitivityLevel.FINANCIAL
            reasons += "Receipt/financial document detected"
            confidence = 0.8f
        }

        // Identity detection
        if (AADHAAR_PATTERN.containsMatchIn(ocrText)) {
            level = SensitivityLevel.IDENTITY
            reasons += "Aadhaar-like number detected"
            confidence = 0.9f
        }
        if (PAN_PATTERN.containsMatchIn(ocrText)) {
            level = SensitivityLevel.IDENTITY
            reasons += "PAN-like identifier detected"
            confidence = 0.9f
        }

        // Personal contact detection
        if (EMAIL_PATTERN.containsMatchIn(ocrText)) {
            if (level == SensitivityLevel.PUBLIC) level = SensitivityLevel.PERSONAL
            reasons += "Email address detected"
            confidence = maxOf(confidence, 0.6f)
        }
        if (PHONE_PATTERN.containsMatchIn(ocrText)) {
            if (level == SensitivityLevel.PUBLIC) level = SensitivityLevel.PERSONAL
            reasons += "Phone number detected"
            confidence = maxOf(confidence, 0.6f)
        }

        // Communication detection (from classification)
        val communicationKeywords = listOf("whatsapp", "telegram", "signal", "sms", "chat", "message", "conversation")
        if (communicationKeywords.any { ocrText.lowercase().contains(it) }) {
            level = SensitivityLevel.PRIVATE_COMMUNICATION
            reasons += "Private communication detected"
            confidence = 0.7f
        }

        // Default if only entities contain sensitive info
        if (reasons.isEmpty() && entities.any { it.contains("otp", ignoreCase = true) || it.contains("password", ignoreCase = true) }) {
            level = SensitivityLevel.AUTHENTICATION
            reasons += "Sensitive entity detected"
            confidence = 0.7f
        }

        return Classification(level, reasons, confidence)
    }

    /**
     * Quick check for highly sensitive content that needs protection.
     */
    fun isHighlySensitive(ocrText: String): Boolean {
        val classification = classify(ocrText)
        return classification.level in listOf(
            SensitivityLevel.AUTHENTICATION,
            SensitivityLevel.FINANCIAL,
            SensitivityLevel.IDENTITY,
            SensitivityLevel.HIGHLY_SENSITIVE,
        )
    }

    /**
     * Returns a safe description for notifications/previews.
     */
    fun getSafeDescription(level: SensitivityLevel): String = when (level) {
        SensitivityLevel.AUTHENTICATION -> "authentication code"
        SensitivityLevel.FINANCIAL -> "financial information"
        SensitivityLevel.IDENTITY -> "identity document"
        SensitivityLevel.PRIVATE_COMMUNICATION -> "private message"
        SensitivityLevel.SENSITIVE -> "sensitive content"
        SensitivityLevel.HIGHLY_SENSITIVE -> "highly sensitive content"
        SensitivityLevel.PERSONAL -> "personal information"
        SensitivityLevel.PUBLIC -> "content"
    }
}