package neth.iecal.curbox.blockers

import neth.iecal.curbox.hardcoded.ReelAppConfig
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.INSTAGRAM_DM_REEL_MARKER
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.REEL_POSITION_MARKER
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.TIKTOK_DM_VIDEO_MARKER
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.reelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelScriptRunnerTest {

    @Test
    fun regularComparatorHasNoDmInboxFlag() {
        val detection = reelDetectionFromScriptResult("author caption", { it })

        assertEquals("author caption", detection.comparator)
        assertFalse(detection.isInstagramReelOpenedFromDmInbox)
    }

    @Test
    fun positionLineIsRemovedAndParsed() {
        val detection = reelDetectionFromScriptResult(
            "${REEL_POSITION_MARKER}42\nauthor\ncaption",
            { it },
            REEL_POSITION_MARKER
        )

        assertEquals("author\ncaption", detection.comparator)
        assertEquals(42, detection.position)
    }

    @Test
    fun missingPositionLineLeavesComparatorAlone() {
        val detection = reelDetectionFromScriptResult(
            "author\ncaption",
            { it },
            REEL_POSITION_MARKER
        )

        assertEquals("author\ncaption", detection.comparator)
        assertNull(detection.position)
    }

    @Test
    fun onlyPositionDetectorsOptIntoScriptPositions() {
        assertEquals(
            REEL_POSITION_MARKER,
            reelData["com.google.android.youtube"]?.positionMarker
        )
        assertNull(reelData[ReelAppConfig.INSTAGRAM_PACKAGE]?.positionMarker)
    }

    @Test
    fun dmInboxMarkerIsRemovedBeforeComparatorIsConsumed() {
        val detection = reelDetectionFromScriptResult(
            "${INSTAGRAM_DM_REEL_MARKER}author caption",
            { it.uppercase() }
        )

        assertEquals("AUTHOR CAPTION", detection.comparator)
        assertTrue(detection.isInstagramReelOpenedFromDmInbox)
        assertFalse(detection.isTikTokVideoOpenedFromDmInbox)
    }

    @Test
    fun tikTokMarkerDoesNotGrantInstagramException() {
        val detection = reelDetectionFromScriptResult("${TIKTOK_DM_VIDEO_MARKER}author", { it.uppercase() })

        assertEquals("AUTHOR", detection.comparator)
        assertTrue(detection.isTikTokVideoOpenedFromDmInbox)
        assertFalse(detection.isInstagramReelOpenedFromDmInbox)
    }

    @Test
    fun markerInCaptionDoesNotGrantAnException() {
        val detection = reelDetectionFromScriptResult("author $TIKTOK_DM_VIDEO_MARKER", { it })

        assertFalse(detection.isTikTokVideoOpenedFromDmInbox)
    }
}
