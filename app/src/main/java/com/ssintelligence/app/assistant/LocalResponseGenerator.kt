package com.ssintelligence.app.assistant

import java.time.Instant
import java.time.ZoneId

/**
 * Turns [Evidence] into an answer string.
 *
 * Two implementations, used in the fallback chain:
 * - [TemplateResponseGenerator]: deterministic, always available, produces a
 *   grounded sentence per intent. This is the default whenever no local LLM is
 *   installed.
 * - A future [LocalLLMGenerator] slots in front of the chain without touching
 *   retrieval or validation.
 *
 * Both must only state facts the evidence supports. The validator enforces it.
 */
object LocalResponseGenerator {

    interface Generator {
        val available: Boolean
        fun generate(query: QueryInterpreter.InterpretedQuery, evidence: List<Evidence>): String
    }

    /** The reality today: no generative model. Falls back to templates. */
    object NoModelGenerator : Generator {
        override val available: Boolean get() = false
        override fun generate(
            query: QueryInterpreter.InterpretedQuery,
            evidence: List<Evidence>,
        ): String = TemplateResponseGenerator.generate(query, evidence)
    }

    fun generate(query: QueryInterpreter.InterpretedQuery, evidence: List<Evidence>): String {
        if (evidence.isEmpty()) return NO_EVIDENCE
        val sensitive = evidence.any { it.sensitivity != SensitivityLevel.NORMAL }
        if (sensitive) return sensitiveSummary(evidence)
        return TemplateResponseGenerator.generate(query, evidence)
    }

    private fun sensitiveSummary(evidence: List<Evidence>): String {
        val banking = evidence.count { it.sensitivity == SensitivityLevel.HIGHLY_SENSITIVE }
        return if (banking > 0) {
            "I found $banking screenshot${if (banking == 1) "" else "s"} that look like banking, identity or password content. " +
                "For privacy the text stays masked until you explicitly reveal it."
        } else {
            "I found ${evidence.size} screenshot${if (evidence.size == 1) "" else "s"} that contain sensitive information. " +
                "The details stay masked until you reveal them."
        }
    }

    val NO_EVIDENCE =
        "I couldn't find a screenshot with that information. Try a different keyword, a broader date range, or search visually."
}

object TemplateResponseGenerator {

    fun generate(query: QueryInterpreter.InterpretedQuery, evidence: List<Evidence>): String {
        val count = evidence.size
        return when (query.intent) {
            AssistantIntent.PRICE_HISTORY -> priceAnswer(evidence)
            AssistantIntent.TIMELINE -> timelineAnswer(evidence)
            AssistantIntent.COUNT -> "$count screenshot${if (count == 1) "" else "s"} matched."
            AssistantIntent.COMPARE, AssistantIntent.CHANGE_DETECTION -> comparisonAnswer(evidence)
            AssistantIntent.SUMMARIZE, AssistantIntent.LIST, AssistantIntent.SEARCH,
            AssistantIntent.FIND, AssistantIntent.RECALL, AssistantIntent.ENTITY_LOOKUP,
            AssistantIntent.RELATIONSHIP, AssistantIntent.EXPLAIN, -> topicalAnswer(query, evidence)
        }
    }

    private fun priceAnswer(evidence: List<Evidence>): String {
        val prices = evidence.flatMap { it.prices }.distinct()
        if (prices.isEmpty()) return "I found matching screenshots but no readable prices in them."
        val byAmount = prices.sortedBy { it.second }
        val cheapest = byAmount.first()
        val lines = buildString {
            append("I found ${prices.size} price${if (prices.size == 1) "" else "s"} across ${evidence.size} screenshot${if (evidence.size == 1) "" else "s"}.")
            val min = byAmount.first()
            append(" The lowest is ${formatPrice(min)}.")
            val max = byAmount.last()
            if (max != min) append(" The highest is ${formatPrice(max)}.")
        }
        return lines
    }

    private fun timelineAnswer(evidence: List<Evidence>): String {
        val ordered = evidence.sortedBy { it.dateAdded }
        val first = ordered.first()
        val last = ordered.last()
        return "I found ${evidence.size} screenshot${if (evidence.size == 1) "" else "s"} in that window, " +
            "from ${formatDate(first.dateAdded)} to ${formatDate(last.dateAdded)}."
    }

    private fun comparisonAnswer(evidence: List<Evidence>): String {
        val prices = evidence.flatMap { it.prices }
        val hosts = evidence.flatMap { it.hosts }.distinct()
        return buildString {
            append("Comparing ${evidence.size} screenshot${if (evidence.size == 1) "" else "s"}: ")
            if (prices.isNotEmpty()) append("${prices.map { formatPrice(it) }.distinct().joinToString()}. ")
            if (hosts.isNotEmpty()) append("from ${hosts.joinToString()}.")
            if (prices.isEmpty() && hosts.isEmpty()) append("no extractable prices or websites differ.")
        }
    }

    private fun topicalAnswer(query: QueryInterpreter.InterpretedQuery, evidence: List<Evidence>): String {
        val entity = query.entityText
        val count = evidence.size
        val pieces = buildList {
            add("I found $count screenshot${if (count == 1) "" else "s"}")
            if (entity != null) add("about $entity")
            val prices = evidence.flatMap { it.prices }.distinct()
            if (prices.isNotEmpty()) add("with ${prices.size} price${if (prices.size == 1) "" else "s"}: ${prices.joinToString { formatPrice(it) }}")
            val hosts = evidence.flatMap { it.hosts }.distinct()
            if (hosts.isNotEmpty()) add("from ${hosts.take(3).joinToString()}")
        }
        return pieces.joinToString(" ") + "."
    }

    private fun formatPrice(p: Pair<String, Double>): String =
        com.ssintelligence.app.search.Currency.format(p.first, p.second)

    private fun formatDate(epochSeconds: Long): String =
        Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
}
