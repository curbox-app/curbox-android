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
        for (input in keywords) {
            val keyword = input.trim()
            if (keyword.isEmpty()) continue

            when {
                keyword.startsWith("r:", ignoreCase = true) ->
                    runCatching { Regex(keyword.substring(2), RegexOption.IGNORE_CASE) }.getOrNull()
                        ?.let { regexes.add(it) }
                keyword.contains('*') || keyword.contains('?') ->
                    regexes.add(wildcardToRegex(normalize(keyword)))
                else -> literals.add(normalize(keyword))
            }
        }
        return regexes to literals
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val optionalSubdomain = pattern.startsWith("*.")
        val glob = if (optionalSubdomain) pattern.removePrefix("*.") else pattern
        val escaped = buildString {
            glob.forEach { character ->
                append(
                    when (character) {
                        '*' -> ".*"
                        '?' -> "."
                        else -> Regex.escape(character.toString())
                    }
                )
            }
        }
        val prefix = when {
            optionalSubdomain -> "^(?:[^/]+\\.)?"
            pattern.startsWith("/") -> "^[^/]+"
            pattern.substringBefore('/').contains('.') -> "^"
            else -> ""
        }
        val suffix = if (optionalSubdomain && !pattern.contains('/')) "(?=$|[/?#])" else ""
        return Regex(prefix + escaped + suffix, RegexOption.IGNORE_CASE)
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')

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
        val url = normalize(urlIdentifier)

        if (keyword.startsWith("/")) {
            val pathStart = url.indexOf('/')
            if (pathStart < 0) return false
            val path = url.substring(pathStart)
            return path == keyword || path.startsWith("$keyword/") ||
                path.startsWith("$keyword?") || path.startsWith("$keyword#")
        }

        if (url == keyword || url.startsWith("$keyword/") ||
            url.startsWith("$keyword?") || url.startsWith("$keyword#")) return true

        if (!keyword.contains('.') && !keyword.contains('/')) {
            val domain = url.substringBefore('/').substringBefore('?').substringBefore('#')
            if (domain.split('.').any { it == keyword }) return true
        }

        return false
    }

    fun matchesPatterns(patterns: Pair<List<Regex>, List<String>>, urlIdentifier: String): Boolean {
        val normalizedUrl = normalize(urlIdentifier)
        val (regexes, literals) = patterns
        return regexes.any { it.containsMatchIn(normalizedUrl) } ||
               literals.any { matchesLiteral(it, normalizedUrl) }
    }
}
