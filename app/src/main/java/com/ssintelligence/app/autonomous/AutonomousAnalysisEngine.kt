package com.ssintelligence.app.autonomous

import com.ssintelligence.app.domain.model.Screenshot
import com.ssintelligence.app.semantic.ScreenshotDocument

/**
 * The autonomous analysis engine (§3).
 *
 * Each detector is independent and deterministic, reading only what the
 * Phase 1-5 pipeline already produced. The engine never modifies or deletes a
 * screenshot: it derives topics, sessions, events, importance, lifecycle and
 * suggestions, and stores them as references.
 */
class AutonomousAnalysisEngine {

    data class Input(
        val screenshot: Screenshot,
        val document: ScreenshotDocument,
        val entities: Set<String>,
        val visualType: String?,
        val visualLayout: String?,
        val isDark: Boolean,
        val colors: List<String>,
        /** Screenshots already analyzed, for session/event detection. */
        val neighbours: List<Neighbour>,
    ) {
        data class Neighbour(
            val screenshot: Screenshot,
            val entities: Set<String>,
            val hosts: List<String>,
            val visualType: String?,
        )
    }

    // ------------------------------------------------------------- topics

    /**
     * Derives the topic of one screenshot from its own content.
     *
     * The subject is the strongest entity or category signal; the label is
     * built from the screenshot's own words. No hardcoded topic list.
     */
    fun detectTopic(input: Input): Topic {
        val subject = input.entities.firstOrNull()
            ?: input.document.ocrText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.take(40)
            ?: "General"
        val signals = buildList {
            if (input.entities.isNotEmpty()) add("entities: ${input.entities.take(3).joinToString()}")
            if (input.visualType != null) add("visual type: ${input.visualType}")
            if (input.colors.isNotEmpty()) add("palette: ${input.colors.joinToString()}")
        }
        return Topic(
            id = "topic-${input.screenshot.id}",
            label = subject.replaceFirstChar { it.uppercaseChar() },
            screenshotIds = listOf(input.screenshot.id),
            subject = subject,
            signals = signals,
        )
    }

    // ------------------------------------------------------------ sessions

    /**
     * Groups a screenshot into a research session.
     *
     * A session is timestamp proximity plus shared subject matter: two
     * screenshots within [SESSION_GAP_SECONDS] that share an entity, host or
     * visual type belong together. A screenshot with no neighbour in time is
     * not forced into a session — adjacency alone is not a session.
     */
    fun detectSession(input: Input): ScreenshotSession? {
        val close = input.neighbours.filter {
            kotlin.math.abs(it.screenshot.dateAdded - input.screenshot.dateAdded) <= SESSION_GAP_SECONDS
        }
        if (close.isEmpty()) return null
        val shared = close.filter { neighbour ->
            neighbour.entities.any { it in input.entities } ||
                neighbour.hosts.any { it in input.document.hosts } ||
                (neighbour.visualType != null && neighbour.visualType == input.visualType)
        }
        if (shared.isEmpty()) return null
        val all = (shared.map { it.screenshot } + input.screenshot).sortedBy { it.dateAdded }
        val subject = input.entities.firstOrNull()?.lowercase()
            ?: input.document.ocrText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40)
            ?: "Research"
        return ScreenshotSession(
            id = "session-${input.screenshot.id}",
            label = subject.replaceFirstChar { it.uppercaseChar() },
            screenshotIds = all.map { it.id },
            startSeconds = all.first().dateAdded,
            endSeconds = all.last().dateAdded,
            subject = subject,
            signals = buildList {
                add("${all.size} screenshots within ${SESSION_GAP_SECONDS / 60} min")
                if (shared.any { it.entities.any { e -> e in input.entities } }) add("shared entities")
                if (shared.any { it.hosts.any { h -> h in input.document.hosts } }) add("shared websites")
            },
        )
    }

    // -------------------------------------------------------------- events

    /**
     * Detects whether a screenshot is part of a meaningful event.
     *
     * Evidence-aware language is the rule: a confirmation screenshot earns
     * "Confirmed", a checkout without confirmation earns "Possible purchase",
     * and a bare search earns nothing. The detector only reports what the
     * screenshots show.
     */
    fun detectEvent(input: Input): ScreenshotEvent? {
        val text = " ${input.document.ocrText.lowercase()} "
        val type = when {
            CONFIRMATION_WORDS.any { text.contains(it) } -> EventType.BOOKING
            CHECKOUT_WORDS.any { text.contains(it) } -> EventType.PURCHASE
            PAYMENT_WORDS.any { text.contains(it) } -> EventType.PAYMENT
            TRAVEL_WORDS.any { text.contains(it) } -> EventType.TRAVEL
            RESEARCH_WORDS.any { text.contains(it) } -> EventType.RESEARCH
            else -> null
        } ?: return null
        val confirmed = CONFIRMATION_WORDS.any { text.contains(it) }
        return ScreenshotEvent(
            id = "event-${input.screenshot.id}",
            type = type,
            confidence = if (confirmed) EventConfidence.CONFIRMED else EventConfidence.POSSIBLE,
            screenshotIds = listOf(input.screenshot.id),
            startSeconds = input.screenshot.dateAdded,
            endSeconds = input.screenshot.dateAdded,
            subject = input.entities.firstOrNull()?.lowercase() ?: type.label,
            signals = buildList {
                if (confirmed) add("confirmation text present")
                if (input.entities.isNotEmpty()) add("entities: ${input.entities.take(3).joinToString()}")
            },
        )
    }

    // ---------------------------------------------------------- importance

    /**
     * Estimates importance from evidence: confirmations, tickets, orders,
     * receipts and unique reference numbers are important; a bare search result
     * is routine. Never used to delete anything.
    */
    fun scoreImportance(input: Input): ImportanceLevel {
        val text = " ${input.document.ocrText.lowercase()} "
        val strong = IMPORTANT_STRONG.any { text.contains(it) }
        val weak = IMPORTANT_WEAK.any { text.contains(it) }
        return when {
            strong -> ImportanceLevel.IMPORTANT
            weak -> ImportanceLevel.POSSIBLY_IMPORTANT
            else -> ImportanceLevel.ROUTINE
        }
    }

    // ----------------------------------------------------------- lifecycle

    /**
     * Estimates a screenshot's lifecycle from its age and content.
     *
     * "Stale" is a suggestion, never a deletion: a screenshot about a product
     * researched six months ago may be outdated, but it stays searchable and can
     * be restored.
     */
    fun analyzeLifecycle(input: Input, nowSeconds: Long): LifecycleState {
        val ageDays = (nowSeconds - input.screenshot.dateAdded) / 86_400
        val text = " ${input.document.ocrText.lowercase()} "
        val temporary = TEMPORARY_WORDS.any { text.contains(it) }
        return when {
            // Temporary content is stale regardless of age: an OTP is outdated
            // the moment it is read, however recently it was screenshotted.
            temporary -> LifecycleState.STALE
            ageDays <= 1 -> LifecycleState.NEW
            ageDays > 180 -> LifecycleState.STALE
            ageDays > 30 -> LifecycleState.REFERENCE
            else -> LifecycleState.ACTIVE
        }
    }

    // ----------------------------------------------------------- suggestions

    /**
     * Builds reviewable suggestions for one screenshot.
     *
     * Every suggestion is a question the user can accept or reject — never an
     * action taken silently.
     */
    fun suggest(input: Input, nowSeconds: Long): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val text = " ${input.document.ocrText.lowercase()} "
        val ageDays = (nowSeconds - input.screenshot.dateAdded) / 86_400

        if (TEMPORARY_WORDS.any { text.contains(it) }) {
            out += Suggestion(
                id = input.screenshot.id,
                kind = SuggestionKind.TEMPORARY,
                screenshotIds = listOf(input.screenshot.id),
                reason = "Looks like temporary content (OTP, verification, error).",
            )
        }
        if (ageDays > 180 && input.document.ocrText.isNotBlank()) {
            out += Suggestion(
                id = input.screenshot.id,
                kind = SuggestionKind.POTENTIALLY_OBSOLETE,
                screenshotIds = listOf(input.screenshot.id),
                reason = "No similar screenshot in over 6 months.",
            )
        }
        if (input.screenshot.fileSize > 15_000_000) {
            out += Suggestion(
                id = input.screenshot.id,
                kind = SuggestionKind.LARGE_FILE,
                screenshotIds = listOf(input.screenshot.id),
                reason = "Larger than most screenshots (${input.screenshot.fileSize / 1_000_000} MB).",
            )
        }
        return out
    }

    companion object {
        /** Screenshots within this window can belong to the same session. */
        const val SESSION_GAP_SECONDS = 1800L

        private val CONFIRMATION_WORDS = listOf(
            "booking confirmed", "confirmation", "order confirmed", "ticket confirmed",
            "reservation confirmed", "payment successful", "booking successful",
        )
        private val CHECKOUT_WORDS = listOf("checkout", "place order", "pay now", "proceed to pay")
        private val PAYMENT_WORDS = listOf("payment", "paid", "transaction", "upi", "card")
        private val TRAVEL_WORDS = listOf("flight", "hotel", "itinerary", "boarding pass", "pnr")
        private val RESEARCH_WORDS = listOf("review", "compare", "vs", "specifications", "price")

        private val IMPORTANT_STRONG = listOf(
            "booking confirmed", "ticket", "order id", "invoice", "receipt", "pnr",
            "confirmation", "payment successful", "tracking",
        )
        private val IMPORTANT_WEAK = listOf("price", "offer", "discount", "booking", "reservation")

        private val TEMPORARY_WORDS = listOf(
            "otp", "one time password", "verification code", "error", "expired",
            "session timeout", "try again",
        )
    }
}
