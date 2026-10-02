package com.ssintelligence.app.assistant

import com.ssintelligence.app.semantic.SensitiveContentDetector

/**
 * Shapes ranked candidates into the [Evidence] facts the response generator
 * and validator are allowed to use.
 *
 * Only evidence the user can tap to — row ids from the local database — is
 * admitted. Sensitive flags are computed here so every downstream surface
 * (answer, notification, suggestion) applies the same masking policy.
 */
class EvidenceContextBuilder {
    /** Top-scored candidates, converted to masked, source-anchored evidence. */
    fun build(ranked: List<EvidenceRanker.Scored>, limit: Int = 8): List<Evidence> =
        ranked.take(limit).mapNotNull { scored -> evidenceFor(scored) }

    private fun evidenceFor(scored: EvidenceRanker.Scored): Evidence? {
        val screenshot = scored.candidate.result.screenshot
        val prices = extractedPrices(scored)
        val hosts = extractedHosts(scored)
        val sensitivity = sensitivityOf(screenshot.ocrText, hosts, prices)
        val signals = buildList {
            if (scored.candidate.entityHit) add("entity match")
            if (scored.candidate.temporalHit) add("in requested period")
            if (scored.candidate.graphHit) add("graph reference")
            if (scored.candidate.engineRank < Int.MAX_VALUE) add("text match")
        }
        return Evidence(
            screenshotId = screenshot.id,
            dateAdded = screenshot.dateAdded,
            filename = screenshot.filename,
            prices = prices,
            hosts = hosts,
            ocrExcerpt = excerpt(screenshot.ocrText),
            entities = emptySet(),
            sensitivity = sensitivity,
            signals = signals,
        )
    }

    private fun extractedPrices(scored: EvidenceRanker.Scored): List<Pair<String, Double>> =
        parsePricesFromOcr(scored.candidate.result.screenshot.ocrText)

    private fun extractedHosts(scored: EvidenceRanker.Scored): List<String> {
        val text = scored.candidate.result.screenshot.ocrText
        return Regex("""[a-z0-9-]+\.[a-z]{2,}""").findAll(text.lowercase())
            .map { it.value }
            .distinct()
            .take(8)
            .toList()
    }

    private fun sensitivityOf(
        ocrText: String,
        hosts: List<String>,
        prices: List<Pair<String, Double>>,
    ): SensitivityLevel {
        val document = com.ssintelligence.app.semantic.ScreenshotDocument(
            screenshotId = 0L,
            ocrText = ocrText,
            filename = "",
            hosts = hosts,
            prices = prices.mapIndexed { i, (currency, amount) ->
                com.ssintelligence.app.domain.model.ExtractedPrice(i.toLong(), 0L, "", currency, amount)
            },
        )
        val kinds = SensitiveContentDetector.detect(document)
        return when {
            kinds.any { it == com.ssintelligence.app.semantic.SensitiveKind.BANKING ||
                it == com.ssintelligence.app.semantic.SensitiveKind.IDENTITY ||
                it == com.ssintelligence.app.semantic.SensitiveKind.PASSWORD } ->
                SensitivityLevel.HIGHLY_SENSITIVE

            kinds.isNotEmpty() -> SensitivityLevel.SENSITIVE
            else -> SensitivityLevel.NORMAL
        }
    }

    private fun excerpt(ocrText: String): String {
        val clean = ocrText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .joinToString(" · ")
        return if (clean.length <= 360) clean else clean.take(360).trimEnd() + "…"
    }

    /** Deterministic price extraction from OCR, mirroring the Phase 1 extractor. */
    private fun parsePricesFromOcr(ocrText: String): List<Pair<String, Double>> {
        val pattern = Regex("""(₹|\$|€|£)\s*([\d,]+(?:\.\d+)?)|(?:rs|inr|usd|eur|gbp)\.?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        return pattern.findAll(ocrText).mapNotNull { match ->
            val symbol = match.groupValues[1]
            val word = match.value.lowercase()
            val currency = when {
                symbol == "₹" || word.contains("rs") || word.contains("inr") -> "INR"
                symbol == "$" || word.contains("usd") -> "USD"
                symbol == "€" || word.contains("eur") -> "EUR"
                symbol == "£" || word.contains("gbp") -> "GBP"
                else -> null
            } ?: return@mapNotNull null
            val amount = (match.groupValues[2].takeIf { it.isNotBlank() } ?: match.groupValues[3])
                .replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            currency to amount
        }.distinct().toList()
    }
}
