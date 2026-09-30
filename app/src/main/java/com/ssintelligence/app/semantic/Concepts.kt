package com.ssintelligence.app.semantic

/**
 * Local concept relations for query expansion (§29, §30).
 *
 * A curated, reviewable map from a concept to the words that express it. When
 * a query contains "travel", the engine also considers "flight", "hotel",
 * "ticket", "PNR" and the rest — not by asking a model, but by looking them up
 * in a table a person can read and correct.
 *
 * This is deliberately conservative. Over-expansion is the main failure mode
 * of query rewriting (§30): every added term is another chance for a false
 * positive. So expansion is bounded (a few concepts per query, a few terms per
 * concept), never rewrites the user's query, and expansion terms are searched
 * as a separate OR clause whose hits rank below direct matches.
 */
object ConceptGraph {

    /**
     * Concept name to the words that express it. Keys are lowercase single
     * words or short phrases as they appear in OCR text.
     */
    val concepts: Map<String, List<String>> = mapOf(
        "travel" to listOf("flight", "hotel", "train", "ticket", "booking", "reservation", "boarding", "pnr", "departure", "arrival", "itinerary", "trip"),
        "flight" to listOf("airline", "boarding", "departure", "arrival", "pnr", "gate", "seat", "airways"),
        "hotel" to listOf("reservation", "checkin", "checkout", "room", "stay", "booking"),
        "shopping" to listOf("order", "cart", "delivery", "price", "offer", "discount", "deal", "buy", "purchase", "store", "product"),
        "deal" to listOf("offer", "discount", "sale", "price", "deal", "coupon", "cashback"),
        "phone" to listOf("smartphone", "mobile", "handset", "device"),
        "smartphone" to listOf("phone", "mobile", "handset"),
        "laptop" to listOf("computer", "notebook", "macbook"),
        "receipt" to listOf("invoice", "bill", "payment", "paid", "total", "receipt"),
        "food" to listOf("restaurant", "order", "delivery", "zomato", "swiggy", "meal", "dining", "menu"),
        "finance" to listOf("bank", "account", "balance", "transaction", "upi", "payment", "statement"),
        "bank" to listOf("account", "balance", "statement", "transaction", "ifsc"),
        "tax" to listOf("gst", "income", "return", "filing", "tds", "invoice", "ca"),
        "education" to listOf("course", "study", "exam", "class", "lecture", "tutorial", "notes", "coaching"),
        "work" to listOf("meeting", "project", "office", "report", "deadline", "presentation", "email"),
        "health" to listOf("doctor", "hospital", "appointment", "prescription", "report", "clinic", "medicine"),
        "entertainment" to listOf("movie", "show", "ticket", "booking", "concert", "series"),
        "message" to listOf("chat", "conversation", "whatsapp", "sms", "message"),
        "social" to listOf("post", "profile", "follower", "like", "comment", "share"),
        "document" to listOf("certificate", "id", "passport", "license", "form", "application"),
        "technology" to listOf("app", "software", "update", "device", "gadget"),
    )

    /** Reverse index: word → concepts that contain it. Built once, lazily. */
    private val wordToConcepts: Map<String, List<String>> by lazy {
        buildMap {
            for ((concept, words) in concepts) {
                for (word in words) {
                    getOrPut(word) { mutableListOf() }.let { (it as MutableList<String>) += concept }
                }
                getOrPut(concept) { mutableListOf() }.let { (it as MutableList<String>) += concept }
            }
        }
    }

    /**
     * Concepts triggered by any of [words], most specific first.
     *
     * A word maps to the concepts that list it; a concept that *is* the word
     * ranks first so "flight" prefers the flight concept over the travel one.
     */
    fun conceptsFor(words: List<String>): List<String> {
        val scored = mutableMapOf<String, Int>()
        for (word in words) {
            val lower = word.lowercase()
            for (concept in wordToConcepts[lower].orEmpty()) {
                // Direct concept mentions count double: "flight ticket" is
                // about flights, not vaguely about travel.
                scored[concept] = (scored[concept] ?: 0) + if (concept == lower) 2 else 1
            }
        }
        val mentioned = words.map { it.lowercase() }.toSet()
        return scored.entries
            .sortedWith(
                // Ties break toward the concept the user actually named:
                // "flight ticket" is flights first, travel second.
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key in mentioned },
            )
            .take(MAX_CONCEPTS)
            .map { it.key }
    }

    /** Expansion terms for the given concepts, excluding words already present. */
    fun expand(concepts: List<String>, exclude: Set<String>): List<String> {
        val excluded = exclude.map { it.lowercase() }.toSet()
        return concepts
            .flatMap { this.concepts[it].orEmpty() }
            .map { it.lowercase() }
            .filter { it !in excluded }
            .distinct()
            .take(MAX_EXPANSION_TERMS)
    }

    private const val MAX_CONCEPTS = 3
    private const val MAX_EXPANSION_TERMS = 12
}

/**
 * Generates related concepts for a query without changing it (§30).
 *
 * The user's query is never rewritten: expansion terms travel alongside it as
 * a separate candidate source, and anything they match is labelled "related
 * to …" rather than presented as a direct hit (§28).
 */
interface QueryExpansionProvider {
    /** Concepts the query touches, most relevant first. */
    fun conceptsForQuery(terms: List<String>): List<String>

    /** Extra search terms derived from those concepts. */
    fun expandTerms(terms: List<String>): List<String>
}

class ConceptQueryExpander : QueryExpansionProvider {
    override fun conceptsForQuery(terms: List<String>): List<String> =
        ConceptGraph.conceptsFor(terms)

    override fun expandTerms(terms: List<String>): List<String> =
        ConceptGraph.expand(ConceptGraph.conceptsFor(terms), terms.toSet())
}
