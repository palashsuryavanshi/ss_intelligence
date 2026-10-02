package com.ssintelligence.app.autonomous

import com.ssintelligence.app.domain.model.Screenshot

/**
 * Phase 6 autonomous intelligence.
 *
 * Everything here is derived from data the Phase 1-5 pipeline already produced —
 * OCR text, entities, visual analysis, the knowledge graph and the assistant's
 * evidence. Nothing is invented, nothing is deleted, and every insight is
 * traceable to the screenshots that produced it.
 */

// ------------------------------------------------------------------ topics

/**
 * A topic that emerged from the library, not a hardcoded category.
 *
 * Topics are assembled from the entities, categories and OCR keywords that
 * actually co-occur across screenshots, so "Pixel 9a Research" appears because
 * the screenshots mention Pixel 9a — not because a list said so.
 */
data class Topic(
    val id: String,
    val label: String,
    val screenshotIds: List<Long>,
    /** What the topic is about, in plain words: "Pixel 9a", "pricing". */
    val subject: String,
    /** The signals that formed this topic, for "why this?" transparency. */
    val signals: List<String>,
)

// ----------------------------------------------------------------- sessions

/** A research session: screenshots that belong to one activity. */
data class ScreenshotSession(
    val id: String,
    val label: String,
    val screenshotIds: List<Long>,
    val startSeconds: Long,
    val endSeconds: Long,
    val subject: String,
    val signals: List<String>,
)

// ------------------------------------------------------------------- events

/** What an event is, derived from evidence. */
enum class EventType(val label: String) {
    RESEARCH("Research"),
    PURCHASE("Possible purchase"),
    BOOKING("Possible booking"),
    TRAVEL("Travel planning"),
    PAYMENT("Possible payment"),
    DOCUMENT("Document"),
    COMMUNICATION("Communication"),
    STUDY("Study"),
    SHOPPING("Shopping"),
    COMPARISON("Price comparison"),
    OTHER("Other"),
}

/**
 * How confident the event detection is. Never a percentage.
 */
enum class EventConfidence(val label: String) {
    CONFIRMED("Confirmed"),
    STRONG("Strong match"),
    POSSIBLE("Possible match"),
    NEEDS_REVIEW("Needs review"),
}

/**
 * A detected event, grounded in the screenshots that produced it.
 *
 * "Possible purchase" is the honest label when only a checkout page exists —
 * the app never claims a purchase happened without a confirmation screenshot.
 */
data class ScreenshotEvent(
    val id: String,
    val type: EventType,
    val confidence: EventConfidence,
    val screenshotIds: List<Long>,
    val startSeconds: Long?,
    val endSeconds: Long?,
    val subject: String,
    val signals: List<String>,
)

// -------------------------------------------------------------- importance

/** Importance, as a word rather than a score. */
enum class ImportanceLevel(val label: String) {
    IMPORTANT("Important"),
    POSSIBLY_IMPORTANT("Possibly important"),
    ROUTINE("Routine"),
}

// --------------------------------------------------------------- lifecycle

/**
 * A screenshot's conceptual lifecycle. This is never a deletion: a "stale"
 * screenshot stays searchable and can be restored.
 */
enum class LifecycleState(val label: String) {
    NEW("New"),
    ACTIVE("Active"),
    REFERENCE("Reference"),
    STALE("Possibly outdated"),
}

// ------------------------------------------------------------------- tags

/** An automatic tag, removable by the user. */
data class SmartTag(
    val screenshotId: Long,
    val label: String,
    val source: String,
)

// -------------------------------------------------------------- suggestions

/** A reviewable suggestion. Nothing is applied without the user. */
enum class SuggestionKind(val label: String) {
    DUPLICATE("Duplicate information"),
    NEAR_DUPLICATE("Near duplicate"),
    REPEATED_INFORMATION("Repeated information"),
    TEMPORARY("Temporary screenshot"),
    POTENTIALLY_OBSOLETE("Potentially obsolete"),
    LARGE_FILE("Large screenshot"),
    UNORGANIZED("Unorganized screenshot"),
}

data class Suggestion(
    val id: Long,
    val kind: SuggestionKind,
    val screenshotIds: List<Long>,
    val reason: String,
    /** True when the user has accepted this suggestion. */
    val accepted: Boolean = false,
)

// ----------------------------------------------------------------- archive

/** Archive state. Archived screenshots stay searchable and indexed. */
enum class ArchiveState { ACTIVE, ARCHIVED }

// ------------------------------------------------------- organization view

/** Everything the smart home and insights screens need, in one read. */
data class OrganizationSummary(
    val topics: List<Topic>,
    val sessions: List<ScreenshotSession>,
    val events: List<ScreenshotEvent>,
    val important: List<Screenshot>,
    val suggestions: List<Suggestion>,
    val insights: List<Insight>,
)

/** One reviewable insight for the insights center. */
data class Insight(
    val id: String,
    val title: String,
    val detail: String,
    val screenshotIds: List<Long>,
    val kind: InsightKind,
)

enum class InsightKind { TOPIC, PRICE_CHANGE, EVENT, DUPLICATE, SESSION, STALE }
