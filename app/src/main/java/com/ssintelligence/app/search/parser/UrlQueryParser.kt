package com.ssintelligence.app.search.parser

/**
 * Understands domain and URL queries (§13).
 *
 * `amazon.in`, `www.amazon.in`, `https://amazon.in` and
 * `https://www.amazon.in/product/123` all normalize to the host `amazon.in`, so
 * one indexed equality check on `extracted_urls.host` finds them all. The
 * original URL is never needed for matching — only the host — which is what
 * makes the comparison a single indexed lookup instead of a `LIKE '%…%'` scan.
 *
 * The host is matched as "this host or a subdomain of it", so searching for
 * `amazon.in` also finds `smile.amazon.in` but not `notamazon.in`.
 */
class UrlQueryParser {

    /**
     * Host-like tokens, optionally with a scheme, path, query or fragment.
     *
     * The TLD group must be alphabetic, which is what keeps version-like
     * strings out: `v1.2`, `192.168.1.1` and `3.5` never match.
     */
    private val urlPattern = Regex(
        """(?i)\b(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,24}(?::\d{1,5})?(?:/[^\s]*)?""",
    )

    fun parse(text: String, consumed: List<QuerySpan> = emptyList()): List<ParsedSpan<String>> {
        if (text.isBlank()) return emptyList()
        val claimed = consumed.toMutableList()
        val out = mutableListOf<ParsedSpan<String>>()

        for (match in urlPattern.findAll(text)) {
            val span = QuerySpan(match.range.first, match.range.last + 1)
            if (claimed.any { it.overlaps(span) }) continue
            val host = hostOf(match.value) ?: continue
            claimed += span
            out += ParsedSpan(host, span)
        }
        return out
    }

    /**
     * Reduces a URL to its comparable host: scheme, credentials, port, path,
     * query and fragment are dropped, and a leading `www.` is removed.
     *
     * Returns null when the remainder is not a usable domain, which keeps
     * single-label or empty hosts out of the index.
     */
    fun hostOf(raw: String): String? {
        var value = raw.trim().lowercase()
        value = value.substringAfter("://", value)
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        value = value.substringAfterLast('@')
        value = value.substringBefore(':')
        value = value.removePrefix("www.").trimEnd('.')
        if (value.isEmpty()) return null
        if (!value.contains('.')) return null
        if (value.startsWith('.') || value.endsWith('.')) return null
        val tld = value.substringAfterLast('.')
        if (tld.length < 2 || !tld.all { it in 'a'..'z' }) return null
        if (value.split('.').any { it.isEmpty() }) return null
        return value
    }
}
