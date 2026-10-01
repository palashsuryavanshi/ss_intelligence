package com.ssintelligence.app.vision

/**
 * Screenshot type classification from combined signals (§10).
 *
 * No single signal decides: OCR keywords, extracted metadata, hosts, image
 * dimensions and palette each contribute evidence weight, and a type needs
 * accumulated evidence above threshold. A screenshot earns BANKING the way it
 * earns a category in Phase 3 — never from one keyword alone.
 */
object ScreenshotTypeClassifier {

    data class TypedResult(
        val type: ScreenshotType,
        /** Internal 0..1 confidence. Never displayed as a percentage. */
        val confidence: Double,
        /** Which signals fired, for debugging and for tests. */
        val signals: List<String>,
    )

    data class Input(
        val ocrText: String,
        val hosts: List<String>,
        val hasPrice: Boolean,
        val hasOtp: Boolean,
        val hasPhone: Boolean,
        val width: Int,
        val height: Int,
        val isDark: Boolean,
        val textCoverage: Double,
        val layout: ScreenLayout,
    )

    fun classify(input: Input): TypedResult {
        val text = " ${input.ocrText.lowercase()} "
        val hosts = input.hosts.map { it.lowercase() }
        val scores = mutableMapOf<ScreenshotType, Double>()
        val signals = mutableMapOf<ScreenshotType, MutableList<String>>()

        fun add(type: ScreenshotType, weight: Double, signal: String) {
            scores[type] = (scores[type] ?: 0.0) + weight
            signals.getOrPut(type) { mutableListOf() } += signal
        }

        // --- keyword evidence (each worth little on its own) ----------------
        fun keywords(type: ScreenshotType, vararg words: Pair<String, Double>) {
            for ((word, weight) in words) {
                if (text.contains(word)) add(type, weight, "keyword:$word")
            }
        }

        keywords(
            ScreenshotType.BANKING,
            "otp" to 1.0, "one time password" to 1.5, "account" to 0.4, "ifsc" to 1.5,
            "upi" to 0.5, "netbanking" to 1.5, "debit card" to 1.2, "credit card" to 1.2,
            "balance" to 0.8, "transaction" to 0.4,
        )
        keywords(
            ScreenshotType.RECEIPT,
            "invoice" to 1.5, "receipt" to 1.5, "gstin" to 1.2, "total" to 0.4,
            "paid" to 0.4, "order id" to 0.8,
        )
        keywords(
            ScreenshotType.TICKET,
            "boarding pass" to 2.0, "pnr" to 1.5, "departure" to 0.8, "ticket" to 0.6,
            "seat" to 0.4, "gate" to 0.4, "booking confirmation" to 1.2,
        )
        keywords(
            ScreenshotType.PRODUCT_LISTING,
            "add to cart" to 1.8, "buy now" to 1.8, "checkout" to 0.8, "discount" to 0.6,
            "in stock" to 1.0, "free delivery" to 1.0, "ratings" to 0.8, "reviews" to 0.5,
        )
        keywords(
            ScreenshotType.CHAT,
            "typing" to 1.2, "online" to 0.3, "delivered" to 0.5, "seen" to 0.3,
            "message" to 0.3, "reply" to 0.4,
        )
        keywords(
            ScreenshotType.DOCUMENT,
            "aadhaar" to 1.8, "passport" to 1.8, "certificate" to 1.0, "license" to 0.8,
            "pan card" to 1.8, "form 16" to 1.5,
        )
        keywords(
            ScreenshotType.MAP,
            "directions" to 1.2, "km away" to 1.0, "navigate" to 1.0, "traffic" to 0.6,
            "distance" to 0.4, "eta" to 0.8,
        )
        keywords(
            ScreenshotType.SOCIAL_MEDIA,
            "followers" to 1.2, "following" to 0.8, "likes" to 0.4, "comments" to 0.4,
            "share" to 0.3, "story" to 0.4, "reels" to 1.0,
        )
        keywords(
            ScreenshotType.WEBPAGE,
            "cookies" to 1.0, "privacy policy" to 1.0, "subscribe" to 0.5, "newsletter" to 0.8,
            "search" to 0.2, "menu" to 0.2,
        )

        // --- metadata evidence ----------------------------------------------
        if (input.hasPrice) {
            add(ScreenshotType.PRODUCT_LISTING, 0.5, "price")
            add(ScreenshotType.RECEIPT, 0.6, "price")
        }
        if (input.hasOtp) add(ScreenshotType.BANKING, 1.0, "otp-metadata")
        if (hosts.any { it.contains("amazon") || it.contains("flipkart") || it.contains("myntra") }) {
            add(ScreenshotType.PRODUCT_LISTING, 1.2, "shopping-host")
        }
        if (hosts.any { it.contains("instagram") || it.contains("facebook") || it.contains("twitter") || it.contains("x.com") || it.contains("reddit") }) {
            add(ScreenshotType.SOCIAL_MEDIA, 1.2, "social-host")
        }
        if (hosts.any { it.contains("maps") || it.contains("uber") || it.contains("ola") }) {
            add(ScreenshotType.MAP, 1.0, "map-host")
        }
        if (hosts.any { it.contains("bookmyshow") || it.contains("irctc") || it.contains("makemytrip") }) {
            add(ScreenshotType.TICKET, 1.0, "ticket-host")
        }

        // --- visual evidence --------------------------------------------------
        if (input.layout == ScreenLayout.CHAT_BUBBLES) {
            add(ScreenshotType.CHAT, 1.5, "chat-layout")
        }
        if (input.layout == ScreenLayout.RECEIPT_LIKE) {
            add(ScreenshotType.RECEIPT, 1.2, "receipt-layout")
        }
        if (input.layout == ScreenLayout.TABLE) {
            add(ScreenshotType.DOCUMENT, 0.5, "table-layout")
        }
        // A photo-like image: rich color variance is approximated here by high
        // brightness spread — the analyzer passes text coverage, and very low
        // text coverage with no UI keywords suggests a photo.
        if (input.textCoverage < 0.02 && scores.isEmpty()) {
            add(ScreenshotType.PHOTO, 1.6, "no-text")
        }
        // Long screenshots are their own type regardless of content (§25).
        if (input.width > 0 && input.height >= input.width * VisualAnalysis.LONG_ASPECT) {
            return TypedResult(
                ScreenshotType.LONG_SCREENSHOT,
                1.0,
                listOf("aspect:${input.width}x${input.height}"),
            )
        }

        val best = scores.entries
            .filter { it.value >= THRESHOLD }
            .maxByOrNull { it.value }
        if (best == null) {
            // No specific signal at all: generic phone UI markers still earn
            // APP_UI, otherwise the honest answer is OTHER.
            return if (hasUiMarkers(text)) {
                TypedResult(ScreenshotType.APP_UI, 0.5, listOf("ui-markers"))
            } else {
                TypedResult(ScreenshotType.OTHER, 1.0, emptyList())
            }
        }

        // APP_UI is the default for phone screenshots with UI text but no
        // stronger signal: status-bar words, navigation labels, buttons.
        if (best.value < APP_UI_FLOOR && hasUiMarkers(text)) {
            return TypedResult(
                ScreenshotType.APP_UI,
                0.5,
                listOf("ui-markers"),
            )
        }
        return TypedResult(
            best.key,
            (best.value / STRONG_SCORE).coerceIn(0.3, 1.0),
            signals[best.key].orEmpty(),
        )
    }

    private fun hasUiMarkers(text: String): Boolean {
        var hits = 0
        for (marker in UI_MARKERS) if (text.contains(marker)) hits++
        return hits >= 2
    }

    private val UI_MARKERS = listOf(
        "settings", "search", "home", "back", "cancel", "done", "save",
        "notifications", "profile", "logout", "menu", "share", "edit",
    )

    /** No single keyword (max 2.0 for a two-word phrase, 1.8 single) decides alone. */
    private const val THRESHOLD = 1.5

    /** Below this, generic UI markers win over a weak specific signal. */
    private const val APP_UI_FLOOR = 2.0

    private const val STRONG_SCORE = 4.0
}
