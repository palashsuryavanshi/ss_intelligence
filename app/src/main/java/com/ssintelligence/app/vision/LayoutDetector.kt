package com.ssintelligence.app.vision

/**
 * Layout detection from OCR block geometry (§11).
 *
 * The Phase 1 pipeline stores line-level boxes in normalized 0..1000
 * coordinates. Their *arrangement* is layout information no text search can
 * see: chat bubbles alternate sides, tables align in columns, receipts are
 * narrow stacks with a total at the bottom. The detectors below are geometric
 * thresholds, not opinions — each returns the evidence it used.
 *
 * Platform-free: operates on [NormRect] so it is unit-testable without Android.
 */
data class NormRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

object LayoutDetector {

    fun detect(blocks: List<NormRect>): ScreenLayout {
        if (blocks.size < 3) return ScreenLayout.NONE
        // Order matters: the most distinctive structures first.
        if (isChat(blocks)) return ScreenLayout.CHAT_BUBBLES
        if (isTable(blocks)) return ScreenLayout.TABLE
        if (isReceiptLike(blocks)) return ScreenLayout.RECEIPT_LIKE
        if (isFormLike(blocks)) return ScreenLayout.FORM_LIKE
        if (isArticle(blocks)) return ScreenLayout.ARTICLE
        return ScreenLayout.NONE
    }

    /**
     * Chat: short lines in mostly-singleton rows, alternating between the left
     * and right halves. The row structure is what separates chat from tables:
     * a table row holds three or more blocks, a chat row holds one bubble.
     */
    private fun isChat(blocks: List<NormRect>): Boolean {
        val lines = blocks.filter { it.width in 60..620 && it.height in 8..120 }
        if (lines.size < 4) return false
        val rows = groupRows(lines)
        if (rows.size < 4) return false
        // Chat rows are bubbles: one or two blocks. A row of three blocks is a
        // table row, not a bubble.
        if (rows.count { it.size <= 2 } < rows.size * 2 / 3) return false
        val ordered = rows.sortedBy { row -> row.minOf { it.top } }
        var alternations = 0
        for (i in 1 until ordered.size) {
            val prevLeft = ordered[i - 1].minOf { it.centerX } < 500
            val currLeft = ordered[i].minOf { it.centerX } < 500
            if (prevLeft != currLeft) alternations++
        }
        return alternations >= 3
    }

    /**
     * Table: at least two rows, each with three or more blocks whose left
     * edges align into columns across rows.
     */
    private fun isTable(blocks: List<NormRect>): Boolean {
        val rows = groupRows(blocks)
        if (rows.size < 2) return false
        val multiColumn = rows.count { it.size >= 3 }
        if (multiColumn < 2) return false
        // Column alignment: the left edges of the first two columns recur.
        val firstCol = rows.mapNotNull { it.minByOrNull { b -> b.left }?.left }
        val aligned = firstCol.count { edge -> firstCol.count { (it - edge).let { d -> d in -30..30 } } >= 2 }
        return aligned >= 2
    }

    /**
     * Receipt-like: a narrow column of short lines (a receipt is ~half the
     * screenshot width) with most lines right-aligned or centered.
     */
    private fun isReceiptLike(blocks: List<NormRect>): Boolean {
        if (blocks.size < 5) return false
        val narrow = blocks.count { it.width in 60..560 }
        if (narrow < blocks.size * 2 / 3) return false
        val rightish = blocks.count { it.centerX > 500 || (it.left in 300..700) }
        return rightish >= blocks.size / 2
    }

    /**
     * Form-like: many short label/value pairs stacked vertically with
     * consistent left alignment — settings pages, checkout forms. Lines are
     * short labels, which is what separates a form from an article: articles
     * run full-width, forms do not.
     */
    private fun isFormLike(blocks: List<NormRect>): Boolean {
        if (blocks.size < 6) return false
        val rows = groupRows(blocks)
        if (rows.size < 5) return false
        val medianWidth = rows.map { row -> row.maxOf { it.width } }.sorted()
            .let { it[it.size / 2] }
        if (medianWidth > 620) return false
        val lefts = rows.mapNotNull { it.minByOrNull { b -> b.left }?.left }
        if (lefts.isEmpty()) return false
        val anchor = lefts.sorted()[lefts.size / 2]
        return lefts.count { (it - anchor) in -60..60 } >= lefts.size * 2 / 3
    }

    /**
     * Article: long full-width lines stacked evenly — news, blogs, docs.
     */
    private fun isArticle(blocks: List<NormRect>): Boolean {
        if (blocks.size < 6) return false
        val long = blocks.count { it.width > 700 && it.height in 10..60 }
        return long >= blocks.size * 2 / 3
    }

    /** Groups blocks into rows by vertical overlap. */
    private fun groupRows(blocks: List<NormRect>): List<List<NormRect>> {
        val sorted = blocks.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<NormRect>>()
        for (block in sorted) {
            val row = rows.firstOrNull { existing ->
                existing.any { member ->
                    val overlap = minOf(member.bottom, block.bottom) - maxOf(member.top, block.top)
                    overlap > 0 && overlap >= minOf(member.height, block.height) / 2
                }
            }
            if (row == null) rows += mutableListOf(block) else row += block
        }
        return rows
    }
}
