package com.ssintelligence.app.ml.extract

import com.ssintelligence.app.domain.model.UrlCandidate

/**
 * URL detection (§13). Recognizes http(s) URLs, www.* hosts and bare domains.
 *
 * Bare words are NOT treated as URLs: a bare candidate requires a dot plus a
 * known TLD (or a path/query). Emails are excluded via lookbehind.
 */
class UrlExtractor {

    // Conservative TLD set covering the screenshots this app targets first,
    // plus common generics. Extended without code changes by editing [tlds].
    private val tlds = setOf(
        "com", "in", "org", "net", "io", "ai", "co", "gov", "edu", "dev",
        "app", "xyz", "info", "biz", "me", "us", "uk", "de", "fr", "jp",
        "au", "ca", "ru", "br", "it", "es", "nl", "se", "ch", "tech",
        "store", "online", "site", "blog", "cloud", "tv", "cc", "ly",
        "sh", "gg", "link", "news", "media", "live", "studio", "pro",
        "shop", "sale", "deals", "kart", "pay", "bank", "health", "plus",
    )

    private val tldAlternation = tlds.joinToString("|")

    // Case-insensitive so OCR output such as "HTTPS://Example.COM" is matched;
    // normalization lowercases the scheme and host afterwards.
    private val candidatePattern = Regex(
        """(?:https?://[^\s<>{}|\\^`"\[\]]+""" +
            """|www\.[^\s<>{}|\\^`"\[\]]+""" +
            """|(?<![\w@])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+(?:$tldAlternation)(?::\d{1,5})?(?:/[^\s<>{}|\\^`"\[\]]*)?)""",
        RegexOption.IGNORE_CASE,
    )

    private val trailingPunctuation = setOf('.', ',', ';', ':', '!', '?', '\'', '"', '’', '”')

    fun extract(text: String): List<UrlCandidate> {
        if (text.isBlank()) return emptyList()
        return candidatePattern.findAll(text)
            .map { it.value }
            .map { trimCandidate(it) }
            .mapNotNull { normalize(it) }
            .distinctBy { it.url }
            .toList()
    }

    private fun trimCandidate(raw: String): String {
        var end = raw.length
        while (end > 0 && raw[end - 1] in trailingPunctuation) end--
        var candidate = raw.substring(0, end)
        // Drop unbalanced trailing closing brackets: "…(see example.com)" OCR case.
        var openParens = 0
        var openBrackets = 0
        var openBraces = 0
        for (c in candidate) {
            when (c) {
                '(' -> openParens++
                ')' -> openParens--
                '[' -> openBrackets++
                ']' -> openBrackets--
                '{' -> openBraces++
                '}' -> openBraces--
            }
        }
        while (candidate.isNotEmpty()) {
            val last = candidate.last()
            if (last == ')' && openParens < 0) {
                candidate = candidate.dropLast(1); openParens++
            } else if (last == ']' && openBrackets < 0) {
                candidate = candidate.dropLast(1); openBrackets++
            } else if (last == '}' && openBraces < 0) {
                candidate = candidate.dropLast(1); openBraces++
            } else {
                break
            }
        }
        return candidate
    }

    private fun normalize(candidate: String): UrlCandidate? {
        if (candidate.isBlank() || candidate.contains(' ')) return null
        val withScheme = if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            candidate
        } else {
            "https://$candidate"
        }
        val afterScheme = withScheme.substringAfter("://")
        val hostPort = afterScheme.substringBefore('/').substringBefore('?')
        val host = hostPort.substringBefore(':').lowercase()
        if (!isValidHost(host)) return null
        val schemeEnd = withScheme.indexOf("://")
        val scheme = withScheme.substring(0, schemeEnd).lowercase()
        val rest = withScheme.substring(schemeEnd + 3)
        // Lowercase scheme + host, preserve path/query case. Drop fragments.
        val hostPart = rest.substringBefore('/').substringBefore('?')
        val pathPart = rest.substring(hostPart.length).substringBefore('#')
        val normalized = "$scheme://${hostPart.lowercase()}$pathPart"
        return UrlCandidate(url = normalized, host = host)
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if ('.' !in host || ".." in host) return false
        if (host.startsWith('-') || host.startsWith('.') || host.endsWith('-') || host.endsWith('.')) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        val tld = labels.last()
        if (!tld.all { it.isLetter() } || tld.length < 2) return false
        // Bare-domain candidates already required a known TLD via regex; for
        // scheme/www candidates any syntactic TLD is acceptable.
        return true
    }
}
