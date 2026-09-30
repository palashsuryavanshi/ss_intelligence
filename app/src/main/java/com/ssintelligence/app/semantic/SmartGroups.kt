package com.ssintelligence.app.semantic

/**
 * Smart groups: automatic collections with honest labels (§24, §26).
 *
 * Groups form from local data only — shared categories, shared hosts, shared
 * phrases — and a group is surfaced only when it has enough members to be
 * worth opening ([MIN_GROUP_SIZE]). Labels come from the members' own terms,
 * never from a model: the most common significant word across member phrases
 * and hosts, falling back to the category label.
 *
 * Clustering runs on demand (after a large indexing job, or when asked), never
 * on every app open (§25). There is no iterative algorithm here: grouping is a
 * single pass over categories and shared hosts, which is O(n) and needs no
 * tuning. A proper embedding clustering can replace the grouping step later
 * without changing the labelling or the UI.
 */
data class SmartGroup(
    val id: String,
    /** Plain-English label, e.g. "Pixel / Phones" or "Travel". */
    val label: String,
    val category: ScreenshotCategory?,
    val memberIds: List<Long>,
    val coverId: Long,
) {
    val size: Int get() = memberIds.size
}

object SmartGroupBuilder {

    fun build(
        members: List<GroupMember>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SmartGroup> {
        if (members.isEmpty()) return emptyList()

        // Group key: strongest non-OTHER category, else the top shared host
        // root, else the top shared word.
        val byKey = mutableMapOf<String, MutableList<GroupMember>>()
        for (member in members) {
            val key = groupKey(member) ?: continue
            byKey.getOrPut(key) { mutableListOf() } += member
        }

        return byKey.entries
            .filter { it.value.size >= MIN_GROUP_SIZE }
            .map { (key, group) ->
                val sorted = group.sortedByDescending { it.dateAdded }
                SmartGroup(
                    id = key,
                    label = labelFor(key, group),
                    category = group.firstNotNullOfOrNull { it.category },
                    memberIds = sorted.map { it.id },
                    // Newest member is the cover: it best represents "now".
                    coverId = sorted.first().id,
                )
            }
            .sortedByDescending { it.size }
            .take(MAX_GROUPS)
    }

    data class GroupMember(
        val id: Long,
        val category: ScreenshotCategory?,
        val hostRoots: List<String>,
        val words: List<String>,
        val dateAdded: Long,
    )

    private fun groupKey(member: GroupMember): String? {
        val category = member.category
        if (category != null && category != ScreenshotCategory.OTHER) {
            return "category:${category.name}"
        }
        val host = member.hostRoots.firstOrNull()
        if (host != null) return "host:$host"
        val word = member.words.firstOrNull { it.length >= 4 }
        if (word != null) return "word:$word"
        return null
    }

    private fun labelFor(key: String, group: List<GroupMember>): String {
        val (kind, value) = key.split(':', limit = 2)
        if (kind == "category") {
            val category = runCatching { ScreenshotCategory.valueOf(value) }.getOrNull()
            // "Pixel / Phones": the category plus the members' most common
            // significant word, which is what makes the group recognizable.
            val topWord = topWord(group)
            return if (category != null && topWord != null &&
                !category.label.equals(topWord, ignoreCase = true)
            ) {
                "${topWord.titleCase()} / ${category.label}"
            } else {
                category?.label ?: value
            }
        }
        return topWord(group)?.titleCase() ?: value
    }

    private fun topWord(group: List<GroupMember>): String? {
        val stop = setOf("screenshot", "image", "photo", "with", "from", "this", "that")
        return group
            .flatMap { it.words }
            .map { it.lowercase() }
            .filter { it.length >= 4 && it !in stop }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.takeIf { count -> group.count { member -> member.words.any { it.equals(count, ignoreCase = true) } } >= 2 }
    }

    private fun String.titleCase(): String =
        split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    /** Fewer than this is a coincidence, not a collection. */
    const val MIN_GROUP_SIZE = 3

    private const val MAX_GROUPS = 12
}
