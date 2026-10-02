package com.ssintelligence.app.assistant

/**
 * Strips anything from a generated answer that can't be traced back to the
 * evidence.
 *
 * This is the anti-hallucination guard. An answer may only contain money
 * amounts, years and hostnames that actually appear in the evidence, and it may
 * never expose a sensitive value. Anything else is removed, and if a sentence
 * becomes empty the sentence is dropped with it.
 */
object ResponseValidator {

    data class Result(val validated: String, val passed: Boolean, val notes: List<String>)

    fun validate(answer: String, evidence: List<Evidence>): Result {
        val notes = mutableListOf<String>()
        val evidencePrices = evidence.flatMap { it.prices.map { (c, a) -> "$c:$a" } }.toSet()
        val evidenceDates = evidence.map { it.dateAdded }.toSet()
        val evidenceHosts = evidence.flatMap { it.hosts }.toSet()
        val evidenceYears = evidenceDates.map { yearOf(it) }.toSet()

        val sentences = answer.split(Regex("(?<=[.!?])\\s+"))
        val kept = sentences.filter { sentence ->
            val supported = isSupported(
                sentence, evidencePrices, evidenceYears, evidenceHosts, evidence,
            )
            if (!supported) notes += "Removed unsupported claim: \"${sentence.trim()}\""
            supported
        }
        val validated = kept.joinToString(" ").trim()
        val sensitive = evidence.any { it.sensitivity != SensitivityLevel.NORMAL }
        val exposesSensitive = SENSITIVE_LEAK.containsMatchIn(validated)
        if (sensitive && exposesSensitive) {
            notes += "Answer referenced sensitive content; masked."
            return Result(
                validated = "I found matching screenshots, but their details are sensitive and stay masked until you reveal them.",
                passed = false,
                notes = notes,
            )
        }
        return Result(validated.ifBlank { "I found screenshots but could not state a verified answer from them." }, notes.isEmpty(), notes)
    }

    private fun isSupported(
        sentence: String,
        evidencePrices: Set<String>,
        evidenceYears: Set<Int>,
        evidenceHosts: Set<String>,
        evidence: List<Evidence>,
    ): Boolean {
        val pricesInSentence = PRICE_PATTERN.findAll(sentence).mapNotNull { parsePriceToken(it.value) }.toList()
        for (price in pricesInSentence) {
            if ("${price.first}:${price.second}" !in evidencePrices) return false
        }
        val yearsInSentence = Regex("\\b(20\\d{2})\\b").findAll(sentence).map { it.value.toInt() }.toList()
        for (year in yearsInSentence) {
            if (year !in evidenceYears) return false
        }
        val hostsInSentence = Regex("[a-z0-9-]+\\.[a-z]{2,}").findAll(sentence.lowercase()).map { it.value }.toList()
        for (host in hostsInSentence) {
            if (evidenceHosts.none { it.endsWith(host) }) return false
        }
        // Counts of screenshots must not exceed what was retrieved.
        val countMatch = COUNT_PATTERN.find(sentence)
        if (countMatch != null) {
            val claimed = countMatch.groupValues[1].toIntOrNull()
            if (claimed != null && claimed > evidence.size) return false
        }
        return true
    }

    private val PRICE_PATTERN = Regex("(₹|€|\$|£)\\s*[\\d,]+(?:\\.\\d+)?|(?:rs|inr|usd|eur|gbp)\\.?\\s*[\\d,]+(?:\\.\\d+)?", RegexOption.IGNORE_CASE)
    private val COUNT_PATTERN = Regex("\\b(\\d+)\\s+screenshots?\\b", RegexOption.IGNORE_CASE)
    private val SENSITIVE_LEAK = Regex("(?i)\\b(otp|password|pin|cvv|aadhaar|pan)\\s*[:#-]?\\s*\\S")

    private fun parsePriceToken(token: String): Pair<String, Double>? {
        val lower = token.lowercase()
        val currency = when {
            token.contains("₹") || lower.contains("rs") || lower.contains("inr") -> "INR"
            token.contains("$") || lower.contains("usd") -> "USD"
            token.contains("€") || lower.contains("eur") -> "EUR"
            token.contains("£") || lower.contains("gbp") -> "GBP"
            else -> return null
        }
        val amount = Regex("[\\d,]+(?:\\.\\d+)?").find(token)?.value?.replace(",", "")?.toDoubleOrNull()
        return amount?.let { currency to it }
    }

    private fun yearOf(epochSeconds: Long): Int =
        java.time.Instant.ofEpochSecond(epochSeconds)
            .atZone(java.time.ZoneId.systemDefault()).year
}
