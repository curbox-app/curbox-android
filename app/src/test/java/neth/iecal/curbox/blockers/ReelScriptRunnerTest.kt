package neth.iecal.curbox.blockers

import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.INSTAGRAM_DM_REEL_MARKER
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.TIKTOK_DM_VIDEO_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelScriptRunnerTest {

    @Test
    fun regularComparatorHasNoDmInboxFlag() {
        val detection = reelDetectionFromScriptResult("author caption") { it }

        assertEquals("author caption", detection.comparator)
        assertFalse(detection.isInstagramReelOpenedFromDmInbox)
    }

    @Test
    fun dmInboxMarkerIsRemovedBeforeComparatorIsConsumed() {
        val detection = reelDetectionFromScriptResult(
            "${INSTAGRAM_DM_REEL_MARKER}author caption"
        ) { it.uppercase() }

        assertEquals("AUTHOR CAPTION", detection.comparator)
        assertTrue(detection.isInstagramReelOpenedFromDmInbox)
        assertFalse(detection.isTikTokVideoOpenedFromDmInbox)
    }

    @Test
    fun tikTokMarkerDoesNotGrantInstagramException() {
        val detection = reelDetectionFromScriptResult("${TIKTOK_DM_VIDEO_MARKER}author") { it.uppercase() }

        assertEquals("AUTHOR", detection.comparator)
        assertTrue(detection.isTikTokVideoOpenedFromDmInbox)
        assertFalse(detection.isInstagramReelOpenedFromDmInbox)
    }

    @Test
    fun markerInCaptionDoesNotGrantAnException() {
        val detection = reelDetectionFromScriptResult("author $TIKTOK_DM_VIDEO_MARKER") { it }

        assertFalse(detection.isTikTokVideoOpenedFromDmInbox)
    }
}
