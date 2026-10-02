package com.ssintelligence.app.assistant

import com.ssintelligence.app.domain.model.Screenshot

/**
 * Phase 5 assistant domain.
 *
 * Evidence-first: every answer is assembled from [Evidence] rows that point at
 * real database records, and [ResponseValidator] strips any sentence whose
 * claims do not trace back to that evidence. No string in an answer may
 * originate outside this module's retrieval path.
 */

/** What the user is trying to do, derived locally from the query text. */
enum class AssistantIntent {
    SEARCH,
    FIND,
    COMPARE,
    SUMMARIZE,
    LIST,
    COUNT,
    TIMELINE,
    PRICE_HISTORY,
    ENTITY_LOOKUP,
    RELATIONSHIP,
    CHANGE_DETECTION,
    RECALL,
    EXPLAIN,
}

/** Internal evidence level. Never surfaced as a fabricated percentage. */
enum class ConfidenceType(val label: String) {
    FOUND_MULTIPLE("Multiple matching screenshots"),
    FOUND_ONE("Found in 1 screenshot"),
    POSSIBLE_MATCH("Possible match"),
    NO_MATCH("No exact match found"),
}

/** How sensitive a piece of evidence is. Drives masking and reveal. */
enum class SensitivityLevel { NORMAL, SENSITIVE, HIGHLY_SENSITIVE }

/** One retrieved supporting fact, anchored to a real screenshot. */
data class Evidence(
    val screenshotId: Long,
    val dateAdded: Long,
    val filename: String,
    /** The prices this screenshot actually contains, as OCR read them. */
    val prices: List<Pair<String, Double>>,
    val hosts: List<String>,
    val ocrExcerpt: String,
    /** Entity labels filed on this screenshot. */
    val entities: Set<String>,
    val sensitivity: SensitivityLevel,
    /** Why this evidence was selected, for the transparency sheet. */
    val signals: List<String>,
)

/** A clickable source shown under an answer. */
data class ScreenshotSource(
    val screenshotId: Long,
    val filename: String,
    val dateAdded: Long,
)

/** A tap target attached to a response. */
sealed interface AssistantAction {
    data object ViewTimeline : AssistantAction
    data object Compare : AssistantAction
    data class AddToCollection(val name: String) : AssistantAction
    data object ViewSources : AssistantAction

    /**
     * Phase 7 action cards (§76): the assistant proposes, the user confirms,
     * and only then does anything execute. The card carries a typed command,
     * never free text.
     */
    data class ProposeAction(
        val label: String,
        val command: AssistantActionRequest,
    ) : AssistantAction
}

/**
 * A typed action command the assistant may propose (§59).
 *
 * No AI-generated text ever reaches an Android API directly: every command is
 * validated before execution, and consequential ones require confirmation.
 */
sealed interface AssistantActionRequest {
    data class CreateReminder(val title: String, val dueEpochMillis: Long?) : AssistantActionRequest
    data class SaveExpense(val merchant: String?, val amount: Double, val currency: String) : AssistantActionRequest
    data class AddToCalendar(val title: String, val startEpochMillis: Long) : AssistantActionRequest
    data class CreateCollection(val name: String) : AssistantActionRequest
    data class OpenUrl(val url: String) : AssistantActionRequest
}

/** The validated, source-anchored result of one user turn. */
data class AssistantResponse(
    val answer: String,
    val sources: List<ScreenshotSource>,
    val relatedEntities: List<String>,
    val actions: List<AssistantAction>,
    val confidenceType: ConfidenceType,
    /** True when the evidence is sensitive and the answer is masked. */
    val requiresReveal: Boolean,
    val intent: AssistantIntent,
    /** The retrieval and validation trail behind this answer. */
    val evidenceChain: EvidenceChain,
)

/** Why this answer? — the transparency trail (§26, §52). */
data class EvidenceChain(
    val parsedIntent: AssistantIntent,
    val entity: String?,
    val priceFilter: String?,
    val dateRange: String?,
    val candidateCount: Int,
    val selectedCount: Int,
    val validationPassed: Boolean,
    val validationNotes: List<String>,
    val signalSummary: List<String>,
)

/** Conversation state carried across turns so "it" and "that" resolve. */
data class AssistantContext(
    val lastEntity: String?,
    val lastPriceFilter: String?,
    val lastDateRange: DateRange?,
    val lastEvidenceIds: List<Long>,
    val lastIntent: AssistantIntent?,
    val lastRequiresReveal: Boolean,
) {
    companion object {
        val Empty = AssistantContext(null, null, null, emptyList(), null, false)
    }
}

/** A concrete calendar window in epoch seconds, inclusive both ends. */
data class DateRange(val startSeconds: Long, val endSeconds: Long) {
    fun contains(epochSeconds: Long): Boolean = epochSeconds in startSeconds..endSeconds

    fun label(): String {
        val start = java.time.Instant.ofEpochSecond(startSeconds)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val end = java.time.Instant.ofEpochSecond(endSeconds)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return if (start == end) start.toString() else "$start → $end"
    }
}

/** One stored user message and its grounded assistant reply. */
data class ConversationTurn(
    val userText: String,
    val response: AssistantResponse,
)

/** A user-saved, reusable memory snapshot referencing real records (§46). */
data class MemorySnapshot(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val screenshotIds: List<Long>,
    val summary: String,
)
