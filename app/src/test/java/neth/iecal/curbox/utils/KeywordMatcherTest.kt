package neth.iecal.curbox.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMatcherTest {

    private fun blocks(keyword: String, url: String): Boolean =
        KeywordMatcher.matchesPatterns(KeywordMatcher.compileKeywords(listOf(keyword)), url)

    private fun allows(keyword: String, url: String): Boolean =
        KeywordMatcher.matchesPatterns(
            KeywordMatcher.compileKeywords(listOf(keyword)),
            url,
            plainWordsMatchAnywhere = false
        )

    @Test
    fun plainWordMatchesSearchQuery() {
        assertTrue(blocks("youtube", "google.com/search?q=youtube"))
        assertTrue(blocks("YouTube", "google.com/search?q=YOUTUBE&hl=en"))
    }

    @Test
    fun plainWordMatchesPath() {
        assertTrue(blocks("youtube", "en.wikipedia.org/wiki/youtube"))
    }

    @Test
    fun plainWordMatchesPartOfDomain() {
        assertTrue(blocks("keyword", "xyzkeyword.xyz"))
    }

    @Test
    fun plainWordStillMatchesDomain() {
        assertTrue(blocks("youtube", "youtube.com"))
        assertTrue(blocks("youtube", "m.youtube.com/watch?v=abc"))
    }

    @Test
    fun keywordWithSpaceMatchesEncodedQuery() {
        assertTrue(blocks("youtube shorts", "google.com/search?q=youtube+shorts"))
        assertTrue(blocks("youtube shorts", "duckduckgo.com/?q=youtube%20shorts"))
    }

    @Test
    fun malformedEncodingDoesNotBreakMatching() {
        assertTrue(blocks("youtube", "google.com/search?q=youtube%"))
    }

    @Test
    fun plainWordDoesNotMatchUnrelatedUrl() {
        assertFalse(blocks("youtube", "google.com/search?q=vimeo"))
    }

    @Test
    fun allowListOnlyMatchesWholeDomainLabels() {
        assertTrue(allows("wikipedia", "en.wikipedia.org/wiki/cats"))
        assertFalse(allows("wikipedia", "youtube.com/watch?v=abc&x=wikipedia"))
        assertFalse(allows("wikipedia", "notwikipedia.com"))
    }

    @Test
    fun domainKeywordsKeepDomainMatching() {
        assertTrue(blocks("youtube.com", "youtube.com/shorts"))
        assertFalse(blocks("youtube.com", "google.com/search?q=youtube.com"))
    }
}
