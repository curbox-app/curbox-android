package neth.iecal.curbox.hardcoded

import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.blockers.uihider.NodeFinder
import org.junit.Assert.assertEquals
import org.junit.Test

class ReelAppConfigTest {
    @Test
    fun snapchatRequiresTheSpotlightTabToBeSelected() {
        val snapchat = requireNotNull(ReelAppConfig.reelData["com.snapchat.android"])
        val selector = NodeFinder.parseSelector(snapchat.viewId)

        assertEquals("Spotlight", selector["desc"])
        assertEquals(true, selector["selected"])
        assertEquals(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, snapchat.eventType)
    }
}
