package com.ssintelligence.app.domain.repository

import com.ssintelligence.app.domain.model.Screenshot

/** Visual analysis summary for one screenshot, as the UI and ranker see it. */
data class VisualInfo(
    val screenshotId: Long,
    val dhash: Long,
    val colors: List<String>,
    val isDark: Boolean,
    val type: String,
    val layout: String,
)

/** One visually similar screenshot with its Hamming distance. */
data class VisualSimilar(
    val screenshot: Screenshot,
    /** Bits differing, 0..64. Never displayed raw — mapped to words. */
    val distanceBits: Int,
)

/** A near-duplicate set: same pixels modulo small changes (§29). */
data class NearDuplicateGroup(
    /** Representative member id. */
    val coverId: Long,
    val memberIds: List<Long>,
    /** Largest pairwise distance in the group, in bits. */
    val maxDistanceBits: Int,
) {
    val size: Int get() = memberIds.size
}

/** A user-created collection with its member count. */
data class CollectionInfo(
    val id: Long,
    val name: String,
    val memberCount: Int,
    val coverId: Long?,
)

/** One timeline day: date plus its screenshots' top categories (§22). */
data class TimelineDay(
    /** Epoch day. Never fabricated — always a day screenshots exist on. */
    val epochDay: Long,
    val screenshots: List<Screenshot>,
    /** Top categories that day, most common first. */
    val categories: List<String>,
)

/** Screenshots that look like one event (§23). */
data class EventGroup(
    /** What ties them: "Booking ABC123", "amazon.in", "Pixel 9a". */
    val label: String,
    val memberIds: List<Long>,
    /** Epoch seconds span, first to last. */
    val startSeconds: Long,
    val endSeconds: Long,
)

/** Adjacent same-day screenshots with heavy text overlap (§24). */
data class SequenceGroup(
    val label: String,
    val memberIds: List<Long>,
)
