package com.ssintelligence.app.search.parser

/**
 * Filler words the parser removes before searching (§7, §23).
 *
 * English only for now, as specified. The design allows more languages to be
 * added by supplying another set — the extractors are language-agnostic and
 * only consult these tables.
 */
object StopWords {

    /**
     * Words that may appear at the *start* of a query and describe the request
     * rather than its subject: "Find the screenshot where I saw Pixel 9a".
     *
     * Leading filler is trimmed from the front so everything after it forms one
     * contiguous phrase run.
     */
    val leadingTrim: Set<String> = setOf(
        // Verbs that address the app.
        "find", "finds", "search", "searches", "show", "shows", "showme", "locate",
        "spot", "get", "got", "open", "view", "give", "tell", "remember", "recall",
        "retrieve", "display", "list", "browse", "pull", "check", "look", "see",
        "saw", "seen",
        // Politeness and framing.
        "can", "could", "would", "will", "please", "pls", "hey", "ok", "okay",
        "do", "does", "did", "i", "me", "my", "we", "us", "you", "your",
        "where", "wherever", "which", "what", "that", "this", "those", "these",
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "with", "and", "or",
        "any", "some", "there", "it", "its", "was", "were", "is", "are", "be",
        "been", "have", "has", "had", "having",
        // Words that introduce a structured constraint.
        "containing", "contains", "contain", "includes", "include", "including",
        "last", "past", "previous", "recent", "recently",
        // Content nouns used to address the app, not to describe the screenshot.
        "screenshot", "screenshots", "image", "images", "pic", "pics", "photo",
        "photos", "shot", "shots", "snap", "snaps", "capture", "captures",
        // Purchase wording that surrounds a price.
        "cost", "costing", "costs", "priced", "price", "prices", "buy", "bought",
        "paid", "pay", "worth", "sold",
    )

    /**
     * Words that may appear at the *end* of a query. Deliberately excludes bare
     * "screen" so a legitimate phrase like "lock screen" survives.
     */
    val trailingTrim: Set<String> = leadingTrim + setOf(
        "thanks", "thankyou", "thank", "now", "again", "exactly", "only",
    )

    /**
     * Pure function and framing words removed from the middle of a query.
     *
     * Content nouns like "screenshot" are included: they address the app rather
     * than describe the screenshot, and none of them is a plausible search
     * term on its own. Bare "screen" is excluded for the same reason it is
     * excluded above — "lock screen" is a real thing to search for.
     */
    val internal: Set<String> = setOf(
        "a", "an", "the", "of", "in", "on", "at", "to", "for", "with", "and", "or",
        "is", "are", "was", "were", "be", "been", "am", "i", "me", "my", "we", "us",
        "you", "your", "it", "its", "that", "this", "these", "those", "there",
        "have", "has", "had", "do", "did", "does", "can", "could", "would", "will",
        "please", "where", "which", "what", "whose", "when", "then", "saw", "see",
        "seen", "if", "so", "as", "by", "from", "about", "into", "than", "up",
        "down", "but",
        "containing", "contains", "contain", "includes", "include", "including",
        "last", "past", "previous", "recent", "recently",
        "screenshot", "screenshots", "image", "images", "pic", "pics", "photo",
        "photos", "shot", "shots", "snap", "snaps", "capture", "captures",
        "find", "finds", "search", "searches", "show", "shows", "showme", "locate",
        "spot", "remember", "recall", "retrieve", "tell", "display", "browse",
        "cost", "costing", "costs", "priced", "buy", "bought", "paid", "pay",
        "worth", "sold",
    )
}
