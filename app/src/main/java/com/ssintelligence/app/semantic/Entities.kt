package com.ssintelligence.app.semantic

import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ExtractedUrl

/**
 * Everything the semantic layer needs about one screenshot, in one place.
 *
 * Built from the Phase 1 extraction tables, so entities are grounded in what
 * OCR actually read rather than inferred by a model (§31, §32). There is no
 * guessing here: a product is a phrase the screenshot contains, a company is a
 * host it links to, a price is an amount it states.
 */
data class ScreenshotDocument(
    val screenshotId: Long,
    val ocrText: String,
    val filename: String,
    /** Lowercased hosts, e.g. `amazon.in`. */
    val hosts: List<String> = emptyList(),
    val prices: List<ExtractedPrice> = emptyList(),
    val urls: List<ExtractedUrl> = emptyList(),
    /** Raw date strings as OCR read them. */
    val dateTexts: List<String> = emptyList(),
    val phoneCount: Int = 0,
    val otpCount: Int = 0,
    val dateAdded: Long = 0L,
) {
    /** The text that gets embedded: OCR plus the filename's distinctive words. */
    fun embeddableText(): String = buildString {
        append(ocrText)
        // Filenames like "Pixel 9a.png" sometimes carry what OCR missed.
        val name = filename.substringBeforeLast('.')
            .replace(Regex("[_\\-]+"), " ")
            .replace(Regex("screenshot", RegexOption.IGNORE_CASE), " ")
            .trim()
        if (name.isNotBlank()) {
            append("\n")
            append(name)
        }
    }.take(MAX_EMBED_CHARS)

    private companion object {
        /** Long OCR dumps are truncated: the head carries the topic. */
        const val MAX_EMBED_CHARS = 4_000
    }
}

/** A lightweight entity grounded in extracted metadata (§31). */
data class EntityRef(
    val kind: EntityKind,
    /** Display form: "Pixel 9a", "amazon.in", "₹39,999". */
    val label: String,
    /** Normalized form used for matching: lowercase host, ISO price, E.164 phone. */
    val normalized: String,
)

enum class EntityKind {
    PRODUCT,
    COMPANY,
    WEBSITE,
    PRICE,
    DATE,
    PHONE,
    ORDER_NUMBER,
    BOOKING_NUMBER,
}

/**
 * Derives entities from a document's extracted metadata.
 *
 * Products are the document's own phrases (passed in from the search layer or
 * the group labeller); everything else comes straight from the normalized
 * extraction tables. Order and booking numbers are recognized by shape
 * (`[A-Z0-9]{6,}` near an order/booking keyword) rather than by a model.
 */
object EntityExtractor {

    /**
     * Order and booking codes, recognized by shape rather than by a model.
     *
     * Two details matter, both found on a real library:
     *
     * - The case-insensitive flag is **scoped to the keyword** (`(?i:…)`).
     *   A global `(?i)` also made the code class match lowercase words, so
     *   `order Protection` filed an ORDER entity named `Protection` on four
     *   screenshots — which then showed up as a fabricated Timeline "event".
     * - The code must contain a **digit**. Real codes do (`7QK2LP`, `ORD998877`);
     *   English words do not. This is what separates a code from a label.
     */
    private val orderContext = Regex(
        """(?i:order|booking|pnr|confirmation|reservation|transaction|awb|tracking)[\s:#.\-]*([A-Z0-9]{6,20})\b""",
    )

    fun extract(document: ScreenshotDocument, phrases: List<String> = emptyList()): List<EntityRef> =
        buildList {
            phrases.forEach { add(EntityRef(EntityKind.PRODUCT, it, it.lowercase())) }
            document.hosts.distinct().forEach { host ->
                val root = host.removePrefix("www.").substringAfterLast('.', host.substringBefore('.'))
                add(EntityRef(EntityKind.COMPANY, root, root.lowercase()))
                add(EntityRef(EntityKind.WEBSITE, host, host.lowercase()))
            }
            document.prices.forEach { price ->
                add(
                    EntityRef(
                        EntityKind.PRICE,
                        com.ssintelligence.app.search.Currency.format(price.currency, price.amount),
                        "${price.currency}:${price.amount}",
                    ),
                )
            }
            document.dateTexts.forEach { add(EntityRef(EntityKind.DATE, it, it.lowercase())) }
            for (match in orderContext.findAll(document.ocrText)) {
                val code = match.groupValues[1]
                // A code without a digit is a label, not an identifier.
                if (!code.any(Char::isDigit)) continue
                val kind = if (match.value.lowercase().contains("book") || match.value.lowercase().contains("pnr")) {
                    EntityKind.BOOKING_NUMBER
                } else {
                    EntityKind.ORDER_NUMBER
                }
                add(EntityRef(kind, code, code.uppercase()))
            }
        }.distinctBy { it.kind to it.normalized }
}

/** A relationship between a screenshot and one entity (§32). */
data class EntityRelation(
    val screenshotId: Long,
    val kind: EntityKind,
    val normalized: String,
    val label: String,
)
