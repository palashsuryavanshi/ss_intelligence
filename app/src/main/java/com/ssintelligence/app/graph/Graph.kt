package com.ssintelligence.app.graph

import com.ssintelligence.app.semantic.EntityKind
import com.ssintelligence.app.semantic.EntityRef

/**
 * Local knowledge graph over screenshots (§12–§16).
 *
 * Relational tables, not a graph database: entities and relationships are
 * rows, and every traversal is a join (§16). The graph answers "everything
 * about Pixel 9a" — its screenshots, its prices, its websites — from data the
 * extractors already produced. Nothing is inferred, nothing is crawled.
 */

/** Entity types. Closed on purpose: one system, not a system per type (§13). */
enum class GraphEntityType(val label: String) {
    PRODUCT("Product"),
    COMPANY("Company"),
    WEBSITE("Website"),
    PRICE("Price"),
    DATE("Date"),
    PHONE("Phone"),
    ORDER("Order"),
    BOOKING("Booking"),
    CATEGORY("Category"),
    LOCATION("Location"),
    EVENT("Event"),
}

/** Relationship kinds. Stored, except derived ones (see below). */
enum class RelationKind(val label: String) {
    MENTIONS("mentions"),
    PRICED_AT("priced at"),
    SOLD_BY("sold by"),
    FOUND_ON("found on"),
    BELONGS_TO("in category"),
    OCCURRED_ON("dated"),
}

/**
 * SIMILAR_TO and DUPLICATE_OF are deliberately *not* stored: similarity comes
 * from embeddings and duplication from content hashes, both recomputed live.
 * Storing them would duplicate the source of truth and go stale the moment an
 * embedding changed. Derived on read, never persisted.
 */

data class GraphEntity(
    val id: Long,
    val type: GraphEntityType,
    /** Canonical display form: "Pixel 9a", "amazon.in", "₹39,999". */
    val displayName: String,
    /** Normalized match key. Unique per type. */
    val normalizedName: String,
)

data class GraphRelation(
    val screenshotId: Long,
    val entityId: Long,
    val kind: RelationKind,
    val confidence: Double,
)

/** One entity page (§17): everything the library knows about one thing. */
data class EntityPage(
    val entity: GraphEntity,
    val screenshotIds: List<Long>,
    /** Distinct price labels seen with this entity, in screenshot-date order. */
    val pricesSeen: List<PricePoint>,
    val websites: List<String>,
    val categories: List<String>,
)

data class PricePoint(
    val label: String,
    /** Epoch seconds of the screenshot it was seen in. Never fabricated (§18). */
    val seenAtSeconds: Long,
)

/**
 * Normalization rules (§14).
 *
 * Conservative by design: merging is allowed only when the strings are
 * unambiguous variants — case, spacing, punctuation. "Pixel9a" and "PIXEL 9A"
 * are the same product; "Apple" alone is *not* Apple Inc. without host
 * evidence (§58), so company entities only ever come from hosts, never from
 * bare OCR words.
 */
object EntityNormalization {

    /** Normalizes a product phrase: case, spacing, letter/digit splits. */
    fun product(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace(Regex("[^a-z0-9]+"), " ")
        // "pixel9a" → "pixel 9a": a letter/digit boundary starts a new token.
        // The reverse split is deliberately absent: "9a" is a model suffix and
        // must stay intact, or "Pixel 9a" would never match itself.
        s = s.replace(Regex("(?<=[a-z])(?=[0-9])"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Normalizes a host: lowercase, no www, no trailing dot. */
    fun website(host: String): String =
        host.lowercase().removePrefix("www.").trimEnd('.')

    /**
     * Company from a host only. Takes the registrable part: strips
     * subdomains (`smile.amazon.in` → `amazon`), because the company is
     * Amazon whichever subdomain served the page.
     */
    fun company(host: String): String {
        val clean = website(host)
        val parts = clean.split('.')
        if (parts.size <= 2) return parts.first()
        // Country-code second levels (co.in, co.uk, com.au) belong to the domain.
        val ccSecond = setOf("co", "com", "org", "net", "gov", "edu")
        return if (parts.size >= 3 && parts[parts.size - 2] in ccSecond) {
            parts[parts.size - 3]
        } else {
            parts[parts.size - 2]
        }
    }

    /** Prices normalize to currency + amount: no conversion, ever. */
    fun price(currency: String, amount: Double): String = "$currency:$amount"

    fun orderOrBooking(code: String): String = code.uppercase().trim()

    fun category(name: String): String = name.lowercase().trim()

    fun date(text: String): String = text.lowercase().trim()
}

/**
 * Builds graph rows from a screenshot's extracted data.
 *
 * Every entity is grounded: products from the screenshot's own phrases,
 * companies and websites from its hosts, prices/dates/phones/order codes from
 * the normalized extraction tables, categories from the classifier. The
 * builder invents nothing — it only files what extraction found (§12).
 */
object GraphBuilder {

    data class BuiltEntity(
        val type: GraphEntityType,
        val displayName: String,
        val normalizedName: String,
    )

    /**
     * Whether a phrase is worth filing as a product.
     *
     * Without a gate, every frequent OCR word becomes a PRODUCT entity: a real
     * library filed `post` (10 screenshots), `search` (9) and `data` (7) as
     * products, which made Explore and the Home suggestion meaningless. Two
     * kinds of evidence are accepted, both grounded in the phrase itself:
     *
     * - **A digit.** Model numbers are the strongest product signal there is:
     *   `Pixel 9a`, `OnePlus 13`, `Note 24`. A bare number is never enough —
     *   the word must be there too.
     * - **A product noun.** `phone`, `laptop`, `earbuds` and friends name a
     *   category of thing that can be bought. A curated list, not a model.
     *
     * Anything else is left out. An entity page is only worth having if it is
     * about something a person could point at, and "the screenshot containing
     * the word post" is not a thing.
     */
    fun looksLikeProduct(phrase: String): Boolean {
        val normalized = EntityNormalization.product(phrase)
        if (normalized.length < 3) return false
        val words = normalized.split(' ')
        if (words.size > 4) return false
        if (words.any { it in WEB_CHROME }) return false
        val hasLetter = words.any { it.any(Char::isLetter) }
        if (!hasLetter) return false
        val hasDigit = words.any { it.any(Char::isDigit) }
        if (hasDigit) return true
        return words.any { it in PRODUCT_NOUNS }
    }

    /**
     * Frequent screenshot chrome that is never a product, even though it is
     * often a perfectly good search term.
     */
    private val WEB_CHROME = setOf(
        "search", "settings", "home", "back", "next", "more", "done", "save", "edit",
        "share", "copy", "paste", "delete", "cancel", "close", "open", "menu", "login",
        "logout", "sign", "account", "profile", "update", "upgrade", "install", "uninstall",
        "download", "upload", "post", "posts", "page", "pages", "view", "views", "data",
        "info", "help", "support", "about", "privacy", "terms", "cookie", "cookies",
        "high", "low", "medium", "new", "old", "yes", "no", "ok", "on", "off", "add",
        "follow", "following", "followers", "like", "likes", "comment", "comments",
        "protection", "tracker", "trackers", "history", "recent", "star", "stars",
        "court", "case", "report", "review", "reviews", "score", "scores",
    )

    /** Nouns that name a category of purchasable thing. */
    private val PRODUCT_NOUNS = setOf(
        "phone", "phones", "smartphone", "smartphones", "mobile", "handset",
        "laptop", "laptops", "notebook", "tablet", "tablets", "watch", "watches",
        "earbuds", "headphones", "headphone", "earphone", "speaker", "speakers",
        "charger", "cable", "case", "cover", "screen", "guard", "glass", "stand",
        "monitor", "television", "tv", "camera", "dslr", "lens", "printer", "router",
        "keyboard", "mouse", "console", "controller", "gpu", "ssd", "ram", "drive",
        "shirt", "tshirt", "jeans", "dress", "saree", "kurta", "shoes", "sneakers",
        "sandals", "bag", "handbag", "wallet", "watch", "ring", "bracelet", "perfume",
        "book", "books", "pen", "notebook", "furniture", "sofa", "bed", "mattress",
        "bike", "bicycle", "car", "tyre", "tires", "helmet", "scooter", "bag",
        "subscription", "plan", "recharge", "offer", "deal", "coupon", "voucher",
    )


    data class BuiltRelation(
        val entity: BuiltEntity,
        val kind: RelationKind,
        val confidence: Double,
    )

    data class Input(
        val phrases: List<String>,
        val hosts: List<String>,
        val prices: List<PriceInput>,
        val dateTexts: List<String>,
        val orderCodes: List<String>,
        val bookingCodes: List<String>,
        val categories: List<String>,
    )

    data class PriceInput(val currency: String, val amount: Double, val label: String)

    fun build(input: Input): List<BuiltRelation> {
        val out = mutableListOf<BuiltRelation>()

        for (phrase in input.phrases.distinct().take(MAX_PRODUCTS)) {
            if (!looksLikeProduct(phrase)) continue
            val normalized = EntityNormalization.product(phrase)
            if (normalized.length < 3) continue
            out += BuiltRelation(
                BuiltEntity(GraphEntityType.PRODUCT, phrase.trim(), normalized),
                RelationKind.MENTIONS,
                0.7,
            )
        }

        for (host in input.hosts.distinct()) {
            val normalizedHost = EntityNormalization.website(host)
            if (!normalizedHost.contains('.')) continue
            out += BuiltRelation(
                BuiltEntity(GraphEntityType.WEBSITE, normalizedHost, normalizedHost),
                RelationKind.FOUND_ON,
                0.9,
            )
            val company = EntityNormalization.company(normalizedHost)
            out += BuiltRelation(
                BuiltEntity(GraphEntityType.COMPANY, company, company),
                RelationKind.SOLD_BY,
                0.6,
            )
        }

        for (price in input.prices.distinctBy { it.currency to it.amount }.take(MAX_PRICES)) {
            out += BuiltRelation(
                BuiltEntity(
                    GraphEntityType.PRICE,
                    price.label,
                    EntityNormalization.price(price.currency, price.amount),
                ),
                RelationKind.PRICED_AT,
                0.9,
            )
        }

        for (code in (input.orderCodes.distinct().take(MAX_CODES))) {
            out += BuiltRelation(
                BuiltEntity(GraphEntityType.ORDER, code, EntityNormalization.orderOrBooking(code)),
                RelationKind.MENTIONS,
                0.8,
            )
        }
        for (code in (input.bookingCodes.distinct().take(MAX_CODES))) {
            out += BuiltRelation(
                BuiltEntity(GraphEntityType.BOOKING, code, EntityNormalization.orderOrBooking(code)),
                RelationKind.MENTIONS,
                0.8,
            )
        }

        for (category in input.categories.distinct()) {
            out += BuiltRelation(
                BuiltEntity(
                    GraphEntityType.CATEGORY,
                    category,
                    EntityNormalization.category(category),
                ),
                RelationKind.BELONGS_TO,
                0.8,
            )
        }

        for (date in input.dateTexts.distinct().take(MAX_DATES)) {
            out += BuiltRelation(
                BuiltEntity(GraphEntityType.DATE, date, EntityNormalization.date(date)),
                RelationKind.OCCURRED_ON,
                0.7,
            )
        }

        return out
    }

    /** Bounds keep a pathological screenshot from exploding the graph. */
    private const val MAX_PRODUCTS = 8
    private const val MAX_PRICES = 8
    private const val MAX_CODES = 6
    private const val MAX_DATES = 6
}

/** Maps Phase 3 [EntityRef]s into graph builder input. */
fun entityRefsToGraphInput(
    refs: List<EntityRef>,
    categories: List<String>,
): GraphBuilder.Input {
    val prices = mutableListOf<GraphBuilder.PriceInput>()
    val orders = mutableListOf<String>()
    val bookings = mutableListOf<String>()
    val dates = mutableListOf<String>()
    for (ref in refs) {
        when (ref.kind) {
            EntityKind.PRICE -> {
                val parts = ref.normalized.split(':')
                if (parts.size == 2) {
                    parts[1].toDoubleOrNull()?.let { amount ->
                        prices += GraphBuilder.PriceInput(parts[0], amount, ref.label)
                    }
                }
            }
            EntityKind.ORDER_NUMBER -> orders += ref.label
            EntityKind.BOOKING_NUMBER -> bookings += ref.label
            EntityKind.DATE -> dates += ref.label
            else -> Unit
        }
    }
    return GraphBuilder.Input(
        phrases = refs.filter { it.kind == EntityKind.PRODUCT }.map { it.label },
        hosts = refs.filter { it.kind == EntityKind.WEBSITE }.map { it.normalized },
        prices = prices,
        dateTexts = dates,
        orderCodes = orders,
        bookingCodes = bookings,
        categories = categories,
    )
}
