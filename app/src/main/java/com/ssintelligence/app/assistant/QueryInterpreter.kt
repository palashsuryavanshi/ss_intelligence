package com.ssintelligence.app.assistant

import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns raw user text into a structured [InterpretedQuery]: intent, entity,
 * price constraint, temporal window, and the visual or attribute hints.
 *
 * Everything here is deterministic and local. Temporal expressions resolve
 * against the real calendar; entity names are carried through to the graph;
 * price values reuse the existing currency-normalization rules. There is no
 * LLM step in understanding — a language model may polish the final answer
 * later, but it never decides what the query means.
 */
object QueryInterpreter {

    data class InterpretedQuery(
        val intent: AssistantIntent,
        /** The entity the user most likely means, verbatim from the query. */
        val entityText: String?,
        /** The price constraint in plain terms, e.g. "under 40000 INR". */
        val priceConstraint: PriceConstraint?,
        val dateRange: DateRange?,
        /** A color the user mentions for visual recall. */
        val color: String?,
        /** True for change/comparison questions. */
        val wantsComparison: Boolean,
        /** What 'it' / 'that' most likely refers to from prior context. */
        val refersToPrevious: Boolean,
        /** Remaining free-text keywords for FTS. */
        val keywords: List<String>,
        /** The original text, for display and debugging. */
        val raw: String,
    ) {
        data class PriceConstraint(val currency: String?, val max: Double?, val min: Double?, val exact: Double?) {
            /** Normalized form, matching the graph's price key. */
            fun describe(): String = when {
                exact != null -> "${currency ?: ""}:$exact"
                max != null && min != null -> "${currency ?: ""}:$min-$max"
                max != null -> "${currency ?: ""}<=:$max"
                min != null -> "${currency ?: ""}>=$min"
                else -> "any"
            }
        }
    }

    fun interpret(raw: String, context: AssistantContext): InterpretedQuery {
        val text = raw.trim()
        val lower = text.lowercase()
        val intent = detectIntent(lower)
        val entity = extractEntity(text)
        val price = extractPrice(lower)
        val dateRange = extractDateRange(lower)
        val color = COLOR_WORDS.firstOrNull { lower.contains(it) }
        val wantsComparison = COMPARISON_MARKERS.any { lower.contains(it) } ||
            intent in setOf(AssistantIntent.COMPARE, AssistantIntent.CHANGE_DETECTION)
        val refersToPrevious = REFERS_TO_PREVIOUS.any { lower.contains(it) }

        // Pronoun-only follow-ups inherit the last turn's subject.
        val entityFromContext = if (entity == null && refersToPrevious && context.lastEntity != null) {
            context.lastEntity
        } else entity
        val priceFromContext = if (price == null && refersToPrevious && context.lastPriceFilter != null) {
            parsePriceFilter(context.lastPriceFilter)
        } else price
        val dateFromContext = if (dateRange == null && refersToPrevious && context.lastDateRange != null) {
            context.lastDateRange
        } else dateRange

        val keywords = text.lowercase()
            .replace(Regex("[^a-z0-9₹$€£\\s.-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }

        return InterpretedQuery(
            intent = intent,
            entityText = entityFromContext,
            priceConstraint = priceFromContext,
            dateRange = dateFromContext,
            color = color,
            wantsComparison = wantsComparison,
            refersToPrevious = refersToPrevious,
            keywords = keywords.distinct(),
            raw = text,
        )
    }

    // ----------------------------------------------------------- intent

    private fun detectIntent(lower: String): AssistantIntent {
        when {
            TIMELINE_WORDS.any { lower.contains(it) } && CHANGE_WORDS.any { lower.contains(it) } ->
                return AssistantIntent.CHANGE_DETECTION

            // Recall and count before entity: "I remember a screenshot about GST"
            // is a memory question, and "how many screenshots about travel" is a
            // count, not an entity lookup.
            RECALL_WORDS.any { lower.contains(it) } -> return AssistantIntent.RECALL
            COUNT_WORDS.any { lower.contains(it) } -> return AssistantIntent.COUNT
            // Entity lookup before list: "show me everything about Japan" is a
            // question about Japan, not a request for a list.
            ENTITY_WORDS.any { lower.contains(it) } -> return AssistantIntent.ENTITY_LOOKUP
            TIMELINE_WORDS.any { lower.contains(it) } -> return AssistantIntent.TIMELINE
            PRICE_WORDS.any { lower.contains(it) } -> return AssistantIntent.PRICE_HISTORY
            COMPARISON_MARKERS.any { lower.contains(it) } -> return AssistantIntent.COMPARE
            CHANGE_WORDS.any { lower.contains(it) } -> return AssistantIntent.CHANGE_DETECTION
            SUMMARIZE_WORDS.any { lower.contains(it) } -> return AssistantIntent.SUMMARIZE
            COUNT_WORDS.any { lower.contains(it) } -> return AssistantIntent.COUNT
            LIST_WORDS.any { lower.contains(it) } -> return AssistantIntent.LIST
            RELATIONSHIP_WORDS.any { lower.contains(it) } -> return AssistantIntent.RELATIONSHIP
            RECALL_WORDS.any { lower.contains(it) } -> return AssistantIntent.RECALL
            EXPLAIN_WORDS.any { lower.contains(it) } -> return AssistantIntent.EXPLAIN
            lower.startsWith("find") || lower.startsWith("show me") || lower.startsWith("which") ->
                return AssistantIntent.FIND

            else -> return AssistantIntent.SEARCH
        }
    }

    // --------------------------------------------------------- entity

    /**
     * Pulls a likely entity out of the query.
     *
     * Deterministic: takes the longest run of capitalized tokens or a known
     * product-shaped phrase. The graph's normalizer decides equality later; this
     * only needs to preserve the user's own words.
     */
    private fun extractEntity(text: String): String? {
        // Explicit domain/entity patterns first.
        val domain = Regex("""[a-z0-9-]+\.[a-z]{2,}""").find(text.lowercase())?.value
        if (domain != null) return domain

        // A capitalized token plus the words that follow it, stopping at a
        // stopword: "What price did I see for the Pixel 9a?" must yield
        // "Pixel 9a", not "Pixel" and not "What I".
        val tokens = text.split(Regex("\\s+"))
        val start = tokens.indexOfFirst { token ->
            token.firstOrNull()?.isUpperCase() == true &&
                token.lowercase().trim(',', '.', '?', '!') !in STOP_WORDS
        }
        if (start < 0) return null
        val run = buildList {
            for (i in start until tokens.size) {
                val token = tokens[i].trim(',', '.', '?', '!')
                if (i > start && token.lowercase() in STOP_WORDS) break
                if (token.isEmpty()) break
                add(token)
            }
        }
        val joined = run.joinToString(" ")
        return if (joined.length >= 2 && joined.any { it.isLetter() }) joined else null
    }

    // --------------------------------------------------------- price

    private fun extractPrice(lower: String): InterpretedQuery.PriceConstraint? {
        val currency = when {
            lower.contains("₹") || lower.contains("rs") || lower.contains("inr") -> "INR"
            lower.contains("$") || lower.contains("usd") -> "USD"
            lower.contains("€") || lower.contains("eur") -> "EUR"
            lower.contains("£") || lower.contains("gbp") -> "GBP"
            else -> null
        }
        // "under 40000", "below 40k", "less than 40000", "up to 40000"
        val under = Regex("""(?:under|below|less than|up to|cheaper than|at most)\s*₹?\s*([\d,]+(?:\.\d+)?)\s*k?""")
            .find(lower)
        if (under != null) {
            val amount = toAmount(under.groupValues[1], under.value.endsWith("k"))
            return InterpretedQuery.PriceConstraint(currency, max = amount, min = null, exact = null)
        }
        // "above 40000", "over 40000", "more than 40000", "from 40000"
        val over = Regex("""(?:above|over|more than|greater than|from|at least)\s*₹?\s*([\d,]+(?:\.\d+)?)\s*k?""")
            .find(lower)
        if (over != null) {
            val amount = toAmount(over.groupValues[1], over.value.endsWith("k"))
            return InterpretedQuery.PriceConstraint(currency, max = null, min = amount, exact = null)
        }
        // "between 30000 and 45000"
        val between = Regex("""between\s*₹?\s*([\d,]+(?:\.\d+)?)\s*k?\s*and\s*₹?\s*([\d,]+(?:\.\d+)?)\s*k?""")
            .find(lower)
        if (between != null) {
            return InterpretedQuery.PriceConstraint(
                currency,
                max = toAmount(between.groupValues[2], between.value.endsWith("k")),
                min = toAmount(between.groupValues[1], false),
                exact = null,
            )
        }
        // An exact amount: "₹39,999" or "39999"
        val exact = Regex("""₹?\s*([\d,]+(?:\.\d+)?)\s*(?:k|lakh|lac)?""").find(lower)
        if (exact != null && lower.contains(Regex("₹|rs|inr|price|under|below|above|over|between|cheapest|lowest|highest"))) {
            val isK = exact.value.contains("k")
            return InterpretedQuery.PriceConstraint(currency, max = null, min = null, exact = toAmount(exact.groupValues[1], isK))
        }
        return null
    }

    private fun toAmount(raw: String, kSuffix: Boolean): Double {
        val v = raw.replace(",", "").toDoubleOrNull() ?: return 0.0
        return if (kSuffix) v * 1000 else v
    }

    private fun parsePriceFilter(label: String): InterpretedQuery.PriceConstraint? =
        extractPrice(label.lowercase())

    // --------------------------------------------------------- temporal

    private fun extractDateRange(lower: String): DateRange? {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now(zone)
        fun fromTo(start: LocalDate, endInclusive: LocalDate) = DateRange(
            startSeconds = start.atStartOfDay(zone).toEpochSecond(),
            endSeconds = endInclusive.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1,
        )
        fun daysAgo(n: Long) = fromTo(now.minusDays(n), now)
        return when {
            lower.contains("today") -> fromTo(now, now)
            lower.contains("yesterday") -> fromTo(now.minusDays(1), now.minusDays(1))
            lower.contains("this week") -> daysAgo(7)
            lower.contains("last week") -> fromTo(now.minusWeeks(1), now)
            lower.contains("this month") -> daysAgo(30)
            lower.contains("last month") -> fromTo(now.minusMonths(1), now)
            lower.contains("recently") || lower.contains("a few days ago") -> daysAgo(14)
            lower.contains("a few weeks ago") -> daysAgo(30)
            else -> null
        }
    }

    // ---------------------------------------------------- cats of words

    private val TIMELINE_WORDS = listOf("before", "after", "around", "earlier", "timeline", "when did", "sequence")
    private val PRICE_WORDS = listOf("price", "cost", "deal", "cheap", "cheapest", "lowest", "highest", "expensive")
    private val COMPARISON_MARKERS = listOf("compare", "changed", "difference", "different", "without", "missing", "vs", "versus")
    private val CHANGE_WORDS = listOf("changed", "change", "different", "missing", "disappeared", "appeared")
    private val SUMMARIZE_WORDS = listOf("summarize", "summary", "main topics", "overview")
    private val COUNT_WORDS = listOf("how many", "count", "number of")
    private val LIST_WORDS = listOf("show me", "list", "all of", "everything")
    private val RELATIONSHIP_WORDS = listOf("what websites", "which sites", "where did", "sold by", "from which", "linked to")
    private val RECALL_WORDS = listOf("i remember", "remember", "blue header", "red button", "white background", "large")
    private val EXPLAIN_WORDS = listOf("why", "explain")
    private val ENTITY_WORDS = listOf("about", "related to", "everything on", "saved about", "collection")
    private val REFERS_TO_PREVIOUS = listOf("it", "that", "this", "the one", "which", "when did i see", "show me that", "the lowest", "the cheapest")
    private val COLOR_WORDS = listOf("blue", "red", "green", "white", "black", "yellow", "purple", "orange", "grey", "gray", "pink")

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "what", "which", "when", "where",
        "how", "did", "do", "does", "for", "in", "on", "at", "to", "of", "and", "or",
        "my", "me", "i", "you", "your", "it", "that", "this", "with", "from", "by",
        "show", "find", "get", "have", "has", "had", "there", "any", "all", "some",
        "under", "below", "above", "over", "about", "around", "before", "after",
        "price", "screenshot", "screenshots", "saved", "see", "saw", "seen",
    )
}
