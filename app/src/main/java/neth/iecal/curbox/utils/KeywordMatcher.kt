package neth.iecal.curbox.utils

import java.util.Locale

/**
 * Pure URL/keyword matching logic shared by the keyword blocker and the UI.
 *
 * A compiled pattern set is a pair of (regexes, lowercase literal keywords).
 */
object KeywordMatcher {

    /**
     * Compiles a collection of keyword patterns into pre-built regexes and literals.
     *
     * Pattern types:
     *   r:<expr>   – raw regex (e.g. r:(?:shorts|reels))
     *   *  / ?     – glob wildcard (* = any chars, ? = one char)
     *   otherwise  – URL-aware literal (domain, path, or plain word)
     */
    fun compileKeywords(keywords: Collection<String>): Pair<List<Regex>, List<String>> {
        val regexes = mutableListOf<Regex>()
        val literals = mutableListOf<String>()
        for (kw in keywords) {
            val lower = kw.lowercase(Locale.ROOT)
            when {
                lower.startsWith("r:") ->
                    runCatching { Regex(lower.removePrefix("r:")) }.getOrNull()
                        ?.let { regexes.add(it) }
                lower.contains('*') || lower.contains('?') ->
                    regexes.add(wildcardToRegex(lower))
                else -> literals.add(lower)
            }
        }
        return regexes to literals
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val escaped = pattern
            .replace(Regex("""[.+^$()|\[\]{}\\]"""), """\\$0""")
            .replace("?", ".")
            .replace("*", ".*")
        // Prepend optional scheme/www only when the pattern looks like a bare domain
        val prefix = if (!pattern.startsWith("http") && !pattern.startsWith("*") &&
                        !pattern.startsWith("/") && !pattern.startsWith("?")) {
            """(?:https?://)?(?:www\.)?"""
        } else ""
        return Regex(prefix + escaped)
    }

    /**
     * URL-aware literal match. [keyword] must already be lowercase.
     * [urlIdentifier] is a domain+path string like "youtube.com/shorts".
     *
     * Handles:
     *   - Exact domain match:   "youtube.com"  → "youtube.com"
     *   - Domain prefix:        "youtube.com"  → "youtube.com/shorts"
     *   - www normalisation:    "www.x.com"    → "x.com/..." and vice-versa
     *   - Path segment:         "/shorts"      → "youtube.com/shorts"
     *   - Domain word:          "youtube"      → "youtube.com", "m.youtube.com"
     */
    private fun matchesLiteral(keyword: String, urlIdentifier: String): Boolean {
        val url = urlIdentifier.lowercase(Locale.ROOT)
        val urlNoWww = url.removePrefix("www.")
        val kwNoWww = keyword.removePrefix("www.")

        if (url == keyword || urlNoWww == kwNoWww) return true

        if (url.startsWith("$keyword/") || url.startsWith("$keyword?") ||
            urlNoWww.startsWith("$kwNoWww/") || urlNoWww.startsWith("$kwNoWww?")) return true

        if (keyword.startsWith("/") && url.contains(keyword)) return true

        if (!keyword.contains('.') && !keyword.contains('/')) {
            val domain = url.substringBefore('/')
            if (domain.split('.').any { it == keyword }) return true
        }

        return false
    }

    fun matchesPatterns(patterns: Pair<List<Regex>, List<String>>, urlIdentifier: String): Boolean {
        val lower = urlIdentifier.lowercase(Locale.ROOT)
        val (regexes, literals) = patterns
        return regexes.any { it.containsMatchIn(lower) } ||
               literals.any { matchesLiteral(it, urlIdentifier) }
    }
}
