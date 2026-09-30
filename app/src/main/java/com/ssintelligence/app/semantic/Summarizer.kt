package com.ssintelligence.app.semantic

/**
 * Concise local summaries (§16, §17).
 *
 * Extractive by default, and honestly so: the summary is assembled from things
 * the screenshot states — a product phrase, a price, a host, a date — joined
 * with dashes. Nothing is paraphrased, nothing is inferred, and nothing can be
 * hallucinated, because no new words are introduced except the separators
 * (§48).
 *
 * A generative local model is deliberately not required. The spec is explicit
 * that a 3–7 GB model merely to generate one sentence is the wrong trade, and
 * this implementation agrees: [LocalSummarizer] is the seam a future model can
 * implement, with the extractive result as its grounded fallback.
 */
interface LocalSummarizer {
    suspend fun summarize(document: ScreenshotDocument, phrases: List<String> = emptyList()): String
}

class ExtractiveSummarizer : LocalSummarizer {

    override suspend fun summarize(document: ScreenshotDocument, phrases: List<String>): String {
        val parts = mutableListOf<String>()

        // Headline: the most distinctive phrase, else the first OCR line.
        val headline = phrases.firstOrNull()?.titleCase()
            ?: document.ocrText.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.length >= 3 }
                ?.take(MAX_HEADLINE)
        headline?.let { parts += it }

        // The largest price is usually the point of a shopping screenshot.
        document.prices.maxByOrNull { it.amount }?.let { price ->
            parts += com.ssintelligence.app.search.Currency.format(price.currency, price.amount)
        }

        // The most specific host, without www.
        document.hosts.minByOrNull { it.length }?.removePrefix("www.")?.let { host ->
            val short = host.split('.').take(2).joinToString(".")
            if (short.isNotBlank()) parts += short
        }

        // A date only when the OCR stated a year; see the detail screen rule.
        document.dateTexts.firstOrNull()?.let { parts += it }

        return parts.distinct().take(MAX_PARTS).joinToString(" — ").ifBlank {
            document.filename.substringBeforeLast('.')
        }
    }

    private fun String.titleCase(): String =
        split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { c -> c.uppercaseChar() }
        }

    private companion object {
        const val MAX_HEADLINE = 60
        const val MAX_PARTS = 4
    }
}
