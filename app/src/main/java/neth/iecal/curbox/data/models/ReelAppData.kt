package neth.iecal.curbox.data.models

import android.view.accessibility.AccessibilityEvent

/**
 * A shipped reel detector. The script returns comparator text while the reel screen is visible,
 * including an empty string when the screen is visible before its comparator node has loaded.
 * Returning null means the current screen is not a reel screen.
 * Position based detectors can seed their first page with [initialComparator] and disable
 * comparator deduplication when positions are reused across viewer sessions.
 * When [pagerClassName] is set, vertical scroll events from that pager report the exact reel
 * position, and the first line of the comparator must identify the reel author.
 */
data class ReelAppData(
    val scriptSource: String,
    val comparisonResultCleanser: (String) -> String = { it },
    val eventType: Int = AccessibilityEvent.TYPE_VIEW_SCROLLED,
    val deduplicateComparators: Boolean = true,
    val initialComparator: String? = null,
    val pagerClassName: String? = null
)
